/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.eclipse.core.runtime.IPath;

import com.ditrix.edt.mcp.server.Activator;

/**
 * Structural (monopolistic) restructure of a standalone-server file infobase through the server's own
 * admin-SSH gate, used by the update / test-run paths that would otherwise dead-end in EDT's
 * "exclusive infobase lock" dialog.
 *
 * <p>Why this exists: a file infobase HOSTED by a standalone (WST) server cannot be restructured by
 * EDT's own {@code IApplicationManager.update}. EDT restarts the server first, and the restarted
 * server holds the file base open, so the platform's exclusive-lock acquisition loops on
 * "Завершить сеансы и повторить" no matter how often the dialog is answered. The restructure must
 * instead run <b>inside the already-running server process</b>, where no external client competes for
 * the base and no exclusive file lock arises. The standalone-server feature exposes an admin shell
 * over SSH ({@code gates.ssh.admin} in the server's {@code config.yaml}); within it the
 * {@code ibcmd infobase config update} command applies the configuration EDT has already loaded into
 * the base, without restarting the server.
 *
 * <p>A restructure via ibcmd is only effective once the NEW configuration is already in the base.
 * EDT loads it as the first phase of its own update, so this must be invoked at the moment EDT is
 * blocked on the exclusive dialog — never pre-launch. When the base is already structurally current
 * the command is a no-op, which keeps the helper idempotent.
 *
 * <p>All resolution here is best-effort: a missing gate, an unreadable {@code config.yaml}, or an
 * absent {@code ssh}/{@code ibcmd} binary degrades to a manual-fallback error rather than a crash.
 * The plugin shells out to {@code ssh} deliberately (ProcessBuilder) — an external process EDT does
 * not spawn itself — inside the server folder, under a hard deadline.
 */
public final class StandaloneInfobaseAdmin
{
    /** Hard deadline for one ssh round-trip (the restructure itself, not including retries). */
    private static final long SSH_TIMEOUT_MS = 5 * 60_000L;

    /** Child-directory (under the workspace) that EDT keeps each standalone server folder in. */
    private static final String SERVERS_DIR = "Серверы"; //$NON-NLS-1$ // "Серверы"

    /** Relative convention for the SSH host key of a standalone-server admin gate. */
    private static final String SSH_KEY_PATH = ".ssh/id_rsa"; //$NON-NLS-1$

    /** Experimental file-based-format marker EDT writes into standalone server configs. */
    private static final String KEY_FILE_BASE = "config.yaml"; //$NON-NLS-1$

    /**
     * Outcome of a best-effort restructure attempt.
     */
    public enum Status
    {
        /** The base was already structurally current; nothing was done (idempotent no-op). */
        ALREADY_CURRENT,
        /** The restructure ran (or was allowed to run) without error; {@code error} is empty. */
        APPLIED,
        /** The restructure could NOT be performed; {@code error} explains why and how to do it by hand. */
        NOT_APPLIED
    }

    /**
     * What a restructure attempt amounts to: a status plus — only when it FAILED — an actionable
     * English error message with the manual fallback command.
     */
    public static final class Outcome
    {
        public final Status status;
        public final String error;

        private Outcome(Status status, String error)
        {
            this.status = status;
            this.error = error;
        }

        static Outcome applied()
        {
            return new Outcome(Status.APPLIED, null);
        }

        static Outcome alreadyCurrent()
        {
            return new Outcome(Status.ALREADY_CURRENT, null);
        }

        static Outcome failed(String error)
        {
            return new Outcome(Status.NOT_APPLIED, error);
        }

        /** @return {@code true} when the restructure is not needed or has been applied. */
        public boolean isSatisfied()
        {
            return status != Status.NOT_APPLIED;
        }
    }

    /**
     * A resolvable admin-SSH gate: how to reach the server's admin shell and what to feed it.
     */
    public static final class Endpoint
    {
        /** The standalone server's folder under the workspace ("Серверы/&lt;name&gt;"). */
        public final File serverFolder;
        /** The SSH host of the admin gate ({@code gates.ssh.admin.address}). */
        public final String host;
        /** The SSH port of the admin gate ({@code gates.ssh.admin.port}). */
        public final int port;
        /** The admin SSH key ({@code &lt;serverFolder&gt;/.ssh/id_rsa}). */
        public final File key;

        Endpoint(File serverFolder, String host, int port, File key)
        {
            this.serverFolder = serverFolder;
            this.host = host;
            this.port = port;
            this.key = key;
        }
    }

    private StandaloneInfobaseAdmin()
    {
        // Utility
    }

    /**
     * Locates the standalone server whose registered infobase is {@code infobaseName} and resolves
     * its admin-SSH gate.
     *
     * @param workspaceRoot the EDT workspace root ({@code ResourcesPlugin.getWorkspace().getRoot().getLocation()})
     * @param infobaseName  the infobase to find, resolved from the update/launch context
     * @return the resolved endpoint, or empty when no matching standalone server with a usable admin
     *         gate was found (callers then fall back to a manual-instruction error)
     */
    public static Optional<Endpoint> resolveEndpoint(IPath workspaceRoot, String infobaseName)
    {
        if (workspaceRoot == null || infobaseName == null || infobaseName.isEmpty())
        {
            return Optional.empty();
        }
        File serversDir = new File(workspaceRoot.toFile(), SERVERS_DIR);
        File[] serverFolders = serversDir.listFiles(File::isDirectory);
        if (serverFolders == null)
        {
            return Optional.empty();
        }
        for (File folder : serverFolders)
        {
            Endpoint endpoint = endpointOf(folder, infobaseName);
            if (endpoint != null)
            {
                return Optional.of(endpoint);
            }
        }
        return Optional.empty();
    }

    /**
     * Reads a single server folder's {@code config.yaml} and, if its infobase is {@code infobaseName},
     * resolves the admin-SSH gate it declares. Returns {@code null} when the folder is not the right
     * server or its gate is unusable.
     */
    private static Endpoint endpointOf(File folder, String infobaseName)
    {
        File config = new File(folder, KEY_FILE_BASE);
        if (!config.isFile())
        {
            return null;
        }
        List<String> lines = readLines(config);
        if (lines == null)
        {
            return null;
        }
        // Tolerant key-path scan of the small, regular YAML EDT writes: "gates.ssh.admin.<address|port>",
        // "infobase.name". Sections are tracked as (name, indent): a line at or above a pushed section's
        // indent pops that section and anything deeper.
        String adminHost = null;
        Integer adminPort = null;
        String registeredName = null;
        List<int[]> indents = new ArrayList<>(); // parallel to the section-name stack: push indent/level
        List<String> stack = new ArrayList<>();
        for (String raw : lines)
        {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) //$NON-NLS-1$
            {
                continue;
            }
            int indent = countLeadingSpaces(raw);
            while (!indents.isEmpty() && indents.get(indents.size() - 1)[0] >= indent)
            {
                indents.remove(indents.size() - 1);
                stack.remove(stack.size() - 1);
            }
            int colon = line.indexOf(':'); //$NON-NLS-1$
            if (colon < 0)
            {
                continue;
            }
            String key = line.substring(0, colon).trim();
            String value = line.substring(colon + 1).trim();
            if (value.isEmpty())
            {
                indents.add(new int[]{indent});
                stack.add(key);
                continue;
            }
            String path = stack.isEmpty() ? key : String.join(".", stack) + "." + key; //$NON-NLS-1$ //$NON-NLS-2$
            if ("infobase.name".equals(path)) //$NON-NLS-1$
            {
                registeredName = unquote(value);
            }
            else if ("gates.ssh.admin.address".equals(path)) //$NON-NLS-1$
            {
                adminHost = unquote(value);
            }
            else if ("gates.ssh.admin.port".equals(path)) //$NON-NLS-1$
            {
                adminPort = parseInt(value);
            }
        }
        if (registeredName == null || !registeredName.equals(infobaseName) || adminHost == null
            || adminPort == null)
        {
            return null;
        }
        File key = new File(folder, SSH_KEY_PATH);
        if (!key.isFile())
        {
            return null;
        }
        return new Endpoint(folder, adminHost, adminPort, key);
    }

    /**
     * Applies any pending structural restructure of the endpoint's infobase through its admin-SSH
     * gate: {@code ssh ... "ibcmd infobase config update"}.
     *
     * @param endpoint the resolved admin gate
     * @return the outcome — {@link Status#APPLIED} or {@link Status#ALREADY_CURRENT} on success,
     *         {@link Status#NOT_APPLIED} with a manual-fallback error otherwise
     */
    /**
     * Locates the standalone server whose infobase is {@code infobaseName} and applies any pending
     * structural restructure through its admin-SSH gate. Idempotent (a no-op when the base is already
     * structurally current). When no usable gate is found — or the ssh/ibcmd step fails — returns a
     * {@link Status#NOT_APPLIED} OUTCOME carrying an actionable manual-fallback error.
     *
     * @param workspaceRoot the EDT workspace root
     * @param infobaseName the infobase to restructure
     * @return the outcome
     */
    public static Outcome restructure(IPath workspaceRoot, String infobaseName)
    {
        Optional<Endpoint> endpoint = resolveEndpoint(workspaceRoot, infobaseName);
        if (!endpoint.isPresent())
        {
            return Outcome.failed("No standalone admin-SSH gate (gates.ssh.admin) found for infobase '" //$NON-NLS-1$
                + infobaseName + "' under <workspace>/" + SERVERS_DIR //$NON-NLS-1$
                + ". The exclusive infobase lock can only be lifted by restructuring inside the " //$NON-NLS-1$
                + "server: log into its admin console from the server folder and run " //$NON-NLS-1$
                + "'infobase config update'."); //$NON-NLS-1$
        }
        return applyConfigRestructure(endpoint.get());
    }

    public static Outcome applyConfigRestructure(Endpoint endpoint)
    {
        if (endpoint == null)
        {
            return Outcome.failed("tool bug: no standalone admin endpoint supplied"); //$NON-NLS-1$
        }
        final Outcome[] holder = new Outcome[1];
        BoundedJob.Result result = BoundedJob.run("Apply structural infobase update via standalone admin", //$NON-NLS-1$
            SSH_TIMEOUT_MS, monitor -> holder[0] = runSsh(endpoint));
        if (!result.isSuccess())
        {
            return cancelledOrTimedOut(endpoint, result, holder[0]);
        }
        return holder[0];
    }

    /**
     * Runs the one-shot ssh command and turns its exit into an {@link Outcome}. Runs on the bounded
     * job thread; never touches the UI.
     */
    private static Outcome runSsh(Endpoint endpoint)
    {
        File key = ensurePrivateKey(endpoint.key);
        List<String> command = new ArrayList<>();
        command.add("ssh"); //$NON-NLS-1$
        command.add("-i"); //$NON-NLS-1$
        command.add(key.getAbsolutePath());
        command.add("-p"); //$NON-NLS-1$
        command.add(String.valueOf(endpoint.port));
        command.add("-o"); //$NON-NLS-1$
        command.add("BatchMode=yes"); //$NON-NLS-1$
        command.add("-o"); //$NON-NLS-1$
        command.add("StrictHostKeyChecking=accept-new"); //$NON-NLS-1$
        command.add("-o"); //$NON-NLS-1$
        command.add("ConnectTimeout=15"); //$NON-NLS-1$
        command.add(endpoint.host);
        command.add("ibcmd infobase config update"); //$NON-NLS-1$
        try
        {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.directory(endpoint.serverFolder);
            pb.redirectErrorStream(true);
            Path out = Files.createTempFile("edt-mcp-admin", ".log"); //$NON-NLS-1$ //$NON-NLS-2$
            pb.redirectOutput(out.toFile());
            Process process = pb.start();
            boolean finished = process.waitFor(SSH_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            if (!finished)
            {
                process.destroyForcibly();
                return Outcome.failed("Standalone admin restructure timed out after "
                    + (SSH_TIMEOUT_MS / 1000) + "s. Run it by hand: " + manualHint(endpoint)); //$NON-NLS-1$ //$NON-NLS-2$
            }
            String log = new String(Files.readAllBytes(out), StandardCharsets.UTF_8);
            Files.deleteIfExists(out);
            if (process.exitValue() == 0)
            {
                // ibcmd applies the loaded configuration; a clean exit means the base is current.
                return Outcome.applied();
            }
            return Outcome.failed("Standalone admin restructure failed (exit " + process.exitValue() //$NON-NLS-1$
                + "): " + tail(log) + " Run it by hand: " + manualHint(endpoint)); //$NON-NLS-1$ //$NON-NLS-2$
        }
        catch (IOException e)
        {
            Activator.logError("Could not start ssh for the standalone admin restructure", e); //$NON-NLS-1$
            return Outcome.failed("Could not run ssh (is it on PATH?): " + e.getMessage() //$NON-NLS-1$
                + " Run the restructure by hand: " + manualHint(endpoint)); //$NON-NLS-1$
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            return Outcome.failed("Standalone admin restructure was interrupted. Run it by hand: " //$NON-NLS-1$
                + manualHint(endpoint)); //$NON-NLS-1$
        }
    }

    /** Outcome when {@link BoundedJob} could not complete the run cleanly. */
    private static Outcome cancelledOrTimedOut(Endpoint endpoint, BoundedJob.Result result, Outcome ran)
    {
        String suffix = " Run it by hand: " + manualHint(endpoint); //$NON-NLS-1$
        switch (result.getOutcome())
        {
        case TIMED_OUT:
            return Outcome.failed("Standalone admin restructure did not finish within " //$NON-NLS-1$
                + (SSH_TIMEOUT_MS / 1000) + "s." + suffix); //$NON-NLS-1$ //$NON-NLS-2$
        case NOT_RUN:
        case TIMED_OUT_BEFORE_START:
            return Outcome.failed("Standalone admin restructure never started (EDT busy)." + suffix); //$NON-NLS-1$
        case INTERRUPTED:
            return Outcome.failed("Standalone admin restructure was interrupted." + suffix); //$NON-NLS-1$
        case COMPLETED:
        default:
            return ran != null ? ran : Outcome.failed("Standalone admin restructure failed." + suffix); //$NON-NLS-1$
        }
    }

    /** The manual-fallback command a user (or operator) can run to unblock the base. */
    private static String manualHint(Endpoint endpoint)
    {
        return "ssh -i " + endpoint.key.getAbsolutePath() + " " + endpoint.host + " -p " //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            + endpoint.port + " \"infobase config update\""; //$NON-NLS-1$
    }

    /**
     * OpenSSH refuses a world/group-readable private key. The server stores its keys world-readable
     * (mode 644), so a safe 0600 COPY is made for the ssh invocation and deleted afterwards. Returns
     * the copy (or the original when it is already private).
     */
    private static File ensurePrivateKey(File key)
    {
        try
        {
            Path path = key.toPath();
            Set<PosixFilePermission> perms = Files.getPosixFilePermissions(path);
            boolean worldOrGroup = perms.contains(PosixFilePermission.GROUP_READ)
                || perms.contains(PosixFilePermission.OTHERS_READ);
            if (!worldOrGroup)
            {
                return key;
            }
            Path copy = Files.createTempFile("edt-mcp-key", ".key"); //$NON-NLS-1$ //$NON-NLS-2$
            Files.copy(path, copy, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            Files.setPosixFilePermissions(copy, EnumSet.of(PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE));
            copy.toFile().deleteOnExit();
            return copy.toFile();
        }
        catch (IOException | UnsupportedOperationException e)
        {
            Activator.logError("Could not prepare the standalone admin SSH key for use", e); //$NON-NLS-1$
            return key;
        }
    }

    private static List<String> readLines(File file)
    {
        try
        {
            return Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
        }
        catch (IOException e)
        {
            return null;
        }
    }

    private static int countLeadingSpaces(String s)
    {
        int n = 0;
        while (n < s.length() && (s.charAt(n) == ' ' || s.charAt(n) == '\t'))
        {
            n++;
        }
        return n;
    }

    private static String unquote(String value)
    {
        if (value.length() >= 2
            && ((value.charAt(0) == '"' && value.charAt(value.length() - 1) == '"')
                || (value.charAt(0) == '\'' && value.charAt(value.length() - 1) == '\'')))
        {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    private static Integer parseInt(String value)
    {
        try
        {
            return Integer.valueOf(value.trim());
        }
        catch (NumberFormatException e)
        {
            return null;
        }
    }

    private static String tail(String log)
    {
        String t = log == null ? "" : log.trim(); //$NON-NLS-1$
        if (t.length() <= 400)
        {
            return t.isEmpty() ? "<no output>" : t; //$NON-NLS-1$
        }
        return t.substring(t.length() - 400);
    }
}
