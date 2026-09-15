/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.core.resources.IProject;

import com.ditrix.edt.mcp.server.Activator;
import com.e1c.g5.dt.applications.IApplication;

/**
 * The external-monopolistic-restructure path for the standalone server (3.0.9). When a structural
 * (monopolistic) infobase update on a standalone {@code ServerApplication.*} target is genuinely
 * needed, EDT asks its exclusive-infobase-lock question. The default answer path
 * ({@link InfobaseExclusiveLockAnswerer}) presses "terminate sessions and retry" — which ends real
 * user sessions on a live server. This class instead runs the 1C-admin procedure: stop the
 * standalone server cleanly, apply the configuration to the underlying <b>file</b> infobase through
 * an external {@code 1cv8 DESIGNER /LoadConfigFromFiles <dir> /UpdateDBCfg} (macOS has no
 * {@code ibcmd}/{@code ring}, but {@code 1cv8} ships DESIGNER), and hand the result back so the
 * caller restarts the server (a retry-{@code launch} — which, with the infobase already current, is
 * an incremental no-op — is the natural restart in the launch/test flow).
 *
 * <p><b>The abort signal.</b> The OSGi question context offers only {@code getMessage()}/
 * {@code getAnswers()} — no infobase or application identifier — so attribution to one application
 * is impossible on that channel. The external mode is therefore a <i>global, armed-windowed</i>
 * switch (the same shape as {@code LaunchUpdateDialogAutoConfirmer}'s armed counters): the tool arms
 * it around its own update/launch call, the answerer, when armed, replies "Cancel" to the
 * exclusive-lock question (aborting the operation cleanly instead of ending sessions) and records
 * {@code requestRestructure}; after the aborted call the tool consumes that signal and runs
 * {@link #escalate}. Safe for the intended dedicated-server deployment; under a concurrent set of
 * MCP calls on several standalone targets at once, all armed calls would be treated as external
 * (a documented residual risk).
 *
 * <p>Reuses the existing standalone-server machinery instead of re-implementing it:
 * {@link StandaloneServerSupport#databaseDirOf} (the file-infobase directory), the bounded stop
 * {@link StandaloneServerStateRecovery#stopStaleServer} (mechanically "stop this application's
 * standalone server" — the name's "stale" refers to its primary caller, not a state gate here), the
 * EDT CLI export {@code IExportConfigurationFilesApi.exportProject} (the same call
 * {@code export_configuration_to_xml} uses), and {@link ExternalProcess} for the bounded shell-out.
 */
public final class StandaloneMonopolisticRestructure
{
    /** How long the external {@code 1cv8 DESIGNER} update may run before it is killed. */
    static final long EXTERNAL_TIMEOUT_MS = 300_000L; // 5 min, then kill

    /** How long to wait, best-effort, for the infobase file to leave its stop-time lock. */
    static final long WAIT_UNLOCK_MS = 30_000L;

    /** Poll interval while waiting for the infobase file to become writable. */
    static final long UNLOCK_POLL_MS = 200L;

    private static final AtomicBoolean ARMED_EXTERNAL = new AtomicBoolean(false);
    private static final AtomicBoolean RESTRUCTURE_REQUESTED = new AtomicBoolean(false);
    private static final AtomicReference<String> LAST_MESSAGE = new AtomicReference<>(null);

    private StandaloneMonopolisticRestructure()
    {
        // Utility class
    }

    /* ------------------------------------------------------------------ *
     *  External-mode signal (localhost between the tool thread and the      *
     *  answerer's callback thread via the static switches).                *
     * ------------------------------------------------------------------ */

    /**
     * Arms "external" mode for the current update/launch. While armed,
     * {@link InfobaseExclusiveLockAnswerer} answers the exclusive-lock question with "Cancel"
     * (aborting cleanly) instead of "terminate sessions and retry".
     */
    public static void armExternal()
    {
        ARMED_EXTERNAL.set(true);
    }

    /**
     * Disarms "external" mode, always paired with {@link #armExternal()} (e.g. in a {@code finally}).
     * Does not clear the recorded request — the caller reads it with {@link #consumeRestructureRequested}.
     */
    public static void disarmExternal()
    {
        ARMED_EXTERNAL.set(false);
    }

    /** @return {@code true} while external mode is armed for the in-flight operation. */
    static boolean isArmedExternal()
    {
        return ARMED_EXTERNAL.get();
    }

    /**
     * Called by {@link InfobaseExclusiveLockAnswerer} when, under an armed external mode, the
     * exclusive-infobase-lock question arrives — i.e. the platform has decided a monopolistic
     * restructure is needed.
     *
     * @param message the question message, kept for the log
     */
    static void requestRestructure(String message)
    {
        RESTRUCTURE_REQUESTED.set(true);
        LAST_MESSAGE.set(message);
    }

    /**
     * Whether the just-aborted operation saw the exclusive-lock question under external mode.
     *
     * @return {@code true} once and then {@code false}, i.e. the caller consumes the one-shot signal
     */
    public static boolean consumeRestructureRequested()
    {
        return RESTRUCTURE_REQUESTED.getAndSet(false);
    }

    /* ------------------------------------------------------------------ *
     *  Orchestration: export -> stop -> wait -> apply.                     *
     *  Restarting the server is the CALLER's job (see Outcome.serverStopped) *
     *  so each flow restarts the way it starts servers.                    *
     * ------------------------------------------------------------------ */

    /**
     * Performs the offline monopolistic update on a file-backed standalone server: export the
     * project to {@code 1C} XML, stop the standalone server, wait for the infobase file to be
     * unlocked, then run {@code 1cv8 DESIGNER /LoadConfigFromFiles <xml> /UpdateDBCfg} on the file.
     *
     * <p>Ordering is deliberate: the export runs first (it needs an open project and touches no
     * server), so a failed export stops nothing. The server is then stopped and &#8212; whatever
     * the apply outcome &#8212; left stopped; the caller MUST restart it ({@link Outcome#serverStopped}
     * says so), or a live production server is left down. On an apply failure the caller still
     * restarts the server and reports that the structure was NOT changed.
     *
     * @param application the standalone-server application (never {@code null})
     * @param applicationId the application id (for messages/logs)
     * @param xmlDir the directory to export the {@code 1C} XML into; created as needed
     * @param binary the resolved {@code 1cv8} executable path (see {@link OneCBinaryResolver})
     * @return the outcome, never {@code null}
     */
    public static Outcome escalate(IApplication application, String applicationId, Path xmlDir,
        String binary)
    {
        Object module = StandaloneServerSupport.moduleOfApplication(application);
        String dbDirStr = module == null ? null : StandaloneServerSupport.databaseDirOf(module);
        if (dbDirStr == null)
        {
            return Outcome.fail("the application is not backed by a file infobase (1Cv8.1CD) that an " //$NON-NLS-1$
                + "external 1cv8 DESIGNER update can target");
        }
        Path dbDir = Path.of(dbDirStr);
        IProject project = application.getProject();
        String projectName = project != null ? project.getName() : applicationId;

        // 1. Export first: no server is touched yet, and a failure here stops nothing.
        try
        {
            exportProject(projectName, xmlDir);
        }
        catch (Exception e)
        {
            Activator.logError("standalone external restructure: export failed; server NOT stopped: " //$NON-NLS-1$
                + applicationId, e);
            return Outcome.fail("exporting the configuration to 1C XML failed; the standalone server " //$NON-NLS-1$
                + "was NOT stopped: " + describe(e));
        }

        // 2. Stop the standalone server through EDT's own application lifecycle.
        StandaloneServerStateRecovery.Recovery recovery =
            StandaloneServerStateRecovery.stopStaleServer(project, applicationId);
        if (!recovery.recovered())
        {
            // A stop that may STILL be running ("failedInFlight") must not be raced with an apply;
            // a stop that never ran leaves the server untouched. Nothing was written.
            return Outcome.fail("stopping the standalone server did not complete (" //$NON-NLS-1$
                + recovery.detail() + "); the external update was NOT applied");
        }

        // 3. Wait, best-effort, for the infobase file lock to be released after the stop.
        waitForFileUnlocked(dbDir);

        // 4. Apply the configuration monopolistically.
        ExternalProcess.Result applied = applyExternal(dbDir, xmlDir, binary);
        if (!applied.succeeded())
        {
            return Outcome.applyFailed(applied);
        }
        return Outcome.applied();
    }

    /**
     * The escalation+retry hand-off used by the launch/test flows ({@code launch},
     * {@code run_yaxunit_tests}). Resolves the {@code 1cv8} binary, exports the project, stops the
     * standalone server, applies the configuration offline and — because {@link #escalate} leaves
     * the server STOPPED — tells the caller to proceed with its retry launch, which is itself the
     * server restart.
     *
     * <p>Returns {@code null} for "proceed with the retry launch" (applied OR apply-failed but
     * server stopped — the relaunch restarts it either way). Returns an error message only for a
     * failure that changed nothing (no binary, unresolvable application, temp-dir failure, or an
     * escalate outcome where the server was NOT stopped).
     *
     * @param application the standalone-server application (never {@code null})
     * @param applicationId the application id (for messages/logs)
     * @param externalUpdate1cBinary the {@code externalUpdate1cBinary} parameter (may be {@code null})
     * @return {@code null} to retry the launch, or an error message to fail with
     */
    public static String escalateForRetry(IApplication application, String applicationId,
        String externalUpdate1cBinary)
    {
        java.util.Optional<String> binaryOpt = OneCBinaryResolver.resolve(externalUpdate1cBinary, null);
        if (!binaryOpt.isPresent())
        {
            return OneCBinaryResolver.notFoundError();
        }
        Path xmlDir;
        try
        {
            xmlDir = Files.createTempDirectory("edt-standalone-restructure"); //$NON-NLS-1$
        }
        catch (java.io.IOException e)
        {
            return "Could not create a temporary export directory for the external standalone-server " //$NON-NLS-1$
                + "restructure: " + e.getMessage(); //$NON-NLS-1$
        }
        Outcome outcome = escalate(application, applicationId, xmlDir, binaryOpt.get());
        if (!outcome.serverStopped && !outcome.applied)
        {
            return "The external standalone-server restructure did not run: " + outcome.message; //$NON-NLS-1$
        }
        Activator.logInfo("Standalone external restructure for " + applicationId //$NON-NLS-1$
            + " (outcome: " + outcome.message + "); retrying the launch"); //$NON-NLS-1$ //$NON-NLS-2$
        return null;
    }

    /** Invokes {@code IExportConfigurationFilesApi.exportProject(projectName, outputPath)}. */
    private static void exportProject(String projectName, Path outputPath) throws Exception
    {
        Object api = Activator.getDefault().getExportConfigurationFilesApi();
        if (api == null)
        {
            throw new IllegalStateException("IExportConfigurationFilesApi is not available (the CLI " //$NON-NLS-1$
                + "API bundle is missing)"); //$NON-NLS-1$
        }
        Files.createDirectories(outputPath);
        Method method = api.getClass().getMethod("exportProject", String.class, Path.class); //$NON-NLS-1$
        method.invoke(api, projectName, outputPath);
    }

    /**
     * Waits (bounded, best-effort) for the infobase file to become writable after the server stop.
     * A healthy server's {@code isTerminated} can flip before the file lock is released, so this
     * closes that gap; on timeout it is a WARNING, not a hard failure (the external {@code 1cv8}
     * will report "base in use" and the apply step fails cleanly).
     *
     * @param dbDir the file-infobase directory (never {@code null})
     */
    private static void waitForFileUnlocked(Path dbDir)
    {
        Path file = dbDir.resolve("1Cv8.1CD"); //$NON-NLS-1$
        if (!Files.exists(file))
        {
            return;
        }
        long deadline = System.currentTimeMillis() + WAIT_UNLOCK_MS;
        try
        {
            while (System.currentTimeMillis() < deadline)
            {
                if (!isHeldForWrite(file))
                {
                    return;
                }
                Thread.sleep(UNLOCK_POLL_MS);
            }
            Activator.logWarning("standalone external restructure: the infobase file was still held " //$NON-NLS-1$
                + "after " + (WAIT_UNLOCK_MS / 1000) + "s: " + file); //$NON-NLS-1$
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Best-effort probe whether another process holds the file for writing. {@code true} when it
     * cannot be locked; {@code false} when a lock is acquired (the update may proceed).
     */
    private static boolean isHeldForWrite(Path file)
    {
        try
        {
            try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(file.toFile(), "rw"); //$NON-NLS-1$
                java.nio.channels.FileChannel ch = raf.getChannel();
                java.nio.channels.FileLock lock = ch.tryLock())
            {
                return lock == null;
            }
        }
        catch (java.nio.channels.OverlappingFileLockException e)
        {
            return true;
        }
        catch (java.io.IOException e)
        {
            // Cannot open for write (e.g. an OS-level exclusive hold) - treated as held.
            return true;
        }
    }

    /**
     * The external {@code 1cv8} update of a FILE infobase.
     *
     * <p>Argument set (macOS): {@code 1cv8 DESIGNER /F"<dir>" /DisableStartupDialogs
     * /LoadConfigFromFiles <xmlDir> /UpdateDBCfg}. {@code /F"<dir>"} targets the file infobase whose
     * {@code 1Cv8.1CD} lives in {@code dbDir}; {@code /LoadConfigFromFiles} reads the
     * {@code exportProject} dump; {@code /UpdateDBCfg} performs the restructure. The {@code /F} path
     * is quoted to match the documented form, and {@code /DisableStartupDialogs} keeps Designers
     * modal free so the bounded run cannot stall on it.
     *
     * @param dbDir the file-infobase directory (never {@code null})
     * @param xmlDir the exported {@code 1C} XML directory (never {@code null})
     * @param binary the {@code 1cv8} executable (never {@code null})
     * @return the bounded process result
     */
    private static ExternalProcess.Result applyExternal(Path dbDir, Path xmlDir, String binary)
    {
        List<String> argv = new ArrayList<>();
        argv.add(binary);
        argv.add("DESIGNER"); //$NON-NLS-1$
        argv.add("/F\"" + dbDir + "\""); //$NON-NLS-1$ //$NON-NLS-2$
        argv.add("/DisableStartupDialogs"); //$NON-NLS-1$
        argv.add("/LoadConfigFromFiles"); //$NON-NLS-1$
        argv.add(xmlDir.toString());
        argv.add("/UpdateDBCfg"); //$NON-NLS-1$
        return ExternalProcess.run(argv, null, EXTERNAL_TIMEOUT_MS);
    }

    /** Collapses an exception to a one-line, cause-aware description. */
    private static String describe(Exception e)
    {
        Throwable cause = e.getCause() != null ? e.getCause() : e;
        String msg = cause.getMessage();
        return msg != null && !msg.isEmpty() ? msg : cause.getClass().getSimpleName();
    }

    /* ------------------------------------------------------------------ *
     *  Outcome                                                             *
     * ------------------------------------------------------------------ */

    /** The result of {@link #escalate}, formatted into a tool message by the caller. */
    public static final class Outcome
    {
        /** Whether the external monopolistic update was applied. */
        public final boolean applied;
        /** Whether the standalone server is currently STOPPED and must be restarted by the caller. */
        public final boolean serverStopped;
        /** Human text explaining the outcome or why it failed. */
        public final String message;

        private Outcome(boolean applied, boolean serverStopped, String message)
        {
            this.applied = applied;
            this.serverStopped = serverStopped;
            this.message = message;
        }

        static Outcome applied()
        {
            return new Outcome(true, true, "external monopolistic update applied; the standalone " //$NON-NLS-1$
                + "server is stopped and must be started"); //$NON-NLS-1$
        }

        static Outcome applyFailed(ExternalProcess.Result r)
        {
            String tail = r.output == null ? "" : r.output.trim(); //$NON-NLS-1$
            if (tail.length() > 400)
            {
                tail = tail.substring(tail.length() - 400);
            }
            return new Outcome(false, true, "the external 1cv8 DESIGNER update failed"
                + (tail.isEmpty() ? "." : " - " + tail)); //$NON-NLS-1$ //$NON-NLS-2$
        }

        static Outcome fail(String reason)
        {
            return new Outcome(false, false, reason);
        }
    }
}
