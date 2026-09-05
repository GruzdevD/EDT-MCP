/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 *
 * edt-mcp-vanessa: git-based plugin self-update support.
 */

package com.ditrix.edt.mcp.server.tools.impl.selfupdate;

import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.eclipse.core.runtime.Platform;
import org.osgi.framework.Bundle;
import org.osgi.framework.FrameworkUtil;

/**
 * The mechanics behind {@code plugin_check_for_update} and {@code plugin_update}.
 *
 * <p>Why git and not a p2-HTTP update site: on the corporate GitLab the only
 * transport that authenticates a private repo is git smart-HTTP (the same one we
 * push with) — anonymous HTTP returns a login redirect, so the native "Check for
 * Updates" cannot reach a private repo. Instead the built plugin jar is committed
 * into an {@code update-site} branch of the plugin's own git repo; these tools
 * fetch that branch with the project's git credential configuration and deploy
 * the newest jar into the local p2 pool, mirroring the manual install.</p>
 *
 * <p>The pure helpers (version parse/compare, jar naming, bundles.info line
 * rewriting) are static and headless-testable; the git subprocess and the running
 * EDT's install location are resolved at call time.</p>
 */
public final class PluginSelfUpdate
{
    /** Tool parameter: remote repository URL. */
    public static final String KEY_REPO = "repositoryUrl"; //$NON-NLS-1$
    /** Tool parameter: artifact branch name. */
    public static final String KEY_BRANCH = "branch"; //$NON-NLS-1$

    /** The personal repo the built plugin artifacts are pushed to. */
    public static final String DEFAULT_REPO =
        "https://gitlab.ozon.ru/dmigruzdev/ozon-edt-mcp.git"; //$NON-NLS-1$
    /** Branch holding the built {@code plugins/*.jar}. */
    public static final String DEFAULT_BRANCH = "update-site"; //$NON-NLS-1$
    /** Bundle symbolic name of this plugin. */
    public static final String SYMBOLIC_NAME = "com.ditrix.edt.mcp.server"; //$NON-NLS-1$
    /** Filename prefix of built bundle jars, {@code <sym>_<version>.jar}. */
    public static final String JAR_PREFIX = SYMBOLIC_NAME + "_"; //$NON-NLS-1$

    /** The out-of-repo git credential config that authenticates gitlab (issue-free for a private repo). */
    private static final String GIT_CONFIG_REL = ".1c-tools/gitlab-config"; //$NON-NLS-1$
    /** Local clone of the artifact branch. */
    private static final String CLONE_SUBDIR = ".1c-tools/vanessa/update-site"; //$NON-NLS-1$
    /** Where manually installed Eclipse/EDT bundles live. */
    private static final String POOL_REL = ".p2/pool/plugins"; //$NON-NLS-1$
    /** Relative path of {@code bundles.info} under the install location. */
    private static final String BUNDLES_INFO_SUBPATH =
        "configuration/org.eclipse.equinox.simpleconfigurator/bundles.info"; //$NON-NLS-1$

    private static final long GIT_TIMEOUT_SECONDS = 120L;

    private PluginSelfUpdate()
    {
        // Static helper only.
    }

    /** Result of a git subprocess. */
    public static final class GitResult
    {
        public final int exitCode;
        public final String output;

        GitResult(int exitCode, String output)
        {
            this.exitCode = exitCode;
            this.output = output;
        }

        public boolean ok()
        {
            return exitCode == 0;
        }
    }

    /** Absolute path of the local artifact clone for {@code $HOME}. */
    public static Path localCloneDir(String home)
    {
        return Paths.get(home, CLONE_SUBDIR.split("/")); //$NON-NLS-1$
    }

    /** Absolute path of the local p2 pool plugins dir for {@code $HOME}. */
    public static Path poolPluginsDir(String home)
    {
        return Paths.get(home, POOL_REL.split("/")); //$NON-NLS-1$
    }

    /**
     * Runs {@code git} from {@code cwd} using the project's gitlab credential
     * config and no terminal prompts (so a failed auth errors instead of hanging).
     *
     * @param home user home (for the credential config location)
     * @param cwd  working directory for the command, or {@code null} to inherit
     * @param args git arguments
     * @return captured result
     */
    public static GitResult git(String home, Path cwd, String... args)
    {
        List<String> cmd = new ArrayList<>();
        cmd.add("git"); //$NON-NLS-1$
        Collections.addAll(cmd, args);

        ProcessBuilder pb = new ProcessBuilder(cmd);
        if (cwd != null)
        {
            pb.directory(cwd.toFile());
        }
        Map<String, String> env = pb.environment();
        Path config = Paths.get(home, GIT_CONFIG_REL.split("/")); //$NON-NLS-1$
        if (Files.isRegularFile(config))
        {
            env.put("GIT_CONFIG_GLOBAL", config.toAbsolutePath().toString()); //$NON-NLS-1$
        }
        env.put("GIT_TERMINAL_PROMPT", "0"); //$NON-NLS-1$
        pb.redirectErrorStream(true);

        Process process = null;
        try
        {
            process = pb.start();
            boolean finished = process.waitFor(GIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            if (!finished)
            {
                process.destroyForcibly();
                return new GitResult(-1, "git timed out: " + String.join(" ", cmd)); //$NON-NLS-1$ //$NON-NLS-2$
            }
            return new GitResult(process.exitValue(), out);
        }
        catch (IOException e)
        {
            return new GitResult(-1, "cannot run git: " + e.getMessage()); //$NON-NLS-1$
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            if (process != null)
            {
                process.destroyForcibly();
            }
            return new GitResult(-1, "git interrupted"); //$NON-NLS-1$
        }
    }

    /**
     * Brings the local clone of the artifact branch up to date with the remote,
     * creating it on first use (exact mirror: fetch + hard reset).
     *
     * @param home   user home
     * @param repo   remote repository URL
     * @param branch artifact branch
     * @return the clone directory
     * @throws IOException when the fetch/clone fails
     */
    public static Path ensureClone(String home, String repo, String branch) throws IOException
    {
        Path cloneDir = localCloneDir(home);
        if (!Files.isDirectory(cloneDir.resolve(".git"))) //$NON-NLS-1$
        {
            Files.createDirectories(cloneDir);
            GitResult clone = git(home, null, "clone", "-q", "-b", branch, repo, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                cloneDir.toString());
            if (!clone.ok())
            {
                throw new IOException("git clone failed: " + clone.output); //$NON-NLS-1$
            }
        }
        else
        {
            GitResult fetch = git(home, cloneDir, "fetch", "-q", "origin"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            if (!fetch.ok())
            {
                throw new IOException("git fetch failed: " + fetch.output); //$NON-NLS-1$
            }
            GitResult reset = git(home, cloneDir, "reset", "-q", "--hard", "origin/" + branch); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            if (!reset.ok())
            {
                throw new IOException("git reset failed: " + reset.output); //$NON-NLS-1$
            }
        }
        return cloneDir;
    }

    /**
     * Extracts the version from a built jar filename
     * ({@code com.ditrix.edt.mcp.server_1.0.0.202609052012.jar} → {@code 1.0.0.202609052012}),
     * or {@code null} when the name is not one of this bundle's jars.
     */
    public static String versionOfJar(String jarName)
    {
        if (jarName == null || !jarName.startsWith(JAR_PREFIX) || !jarName.endsWith(".jar")) //$NON-NLS-1$
        {
            return null;
        }
        return jarName.substring(JAR_PREFIX.length(), jarName.length() - ".jar".length()); //$NON-NLS-1$
    }

    /**
     * Name (not path) of the highest-versioned built jar of this bundle inside
     * {@code pluginsDir}, or {@code null} when none is present.
     */
    public static String highestBundleJar(Path pluginsDir)
    {
        if (pluginsDir == null || !Files.isDirectory(pluginsDir))
        {
            return null;
        }
        String best = null;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(pluginsDir, JAR_PREFIX + "*.jar")) //$NON-NLS-1$
        {
            for (Path p : stream)
            {
                String name = p.getFileName().toString();
                if (best == null || compare(parse(versionOfJar(name)), parse(versionOfJar(best))) > 0)
                {
                    best = name;
                }
            }
        }
        catch (IOException e)
        {
            return null;
        }
        return best;
    }

    /**
     * Parses a dotted plugin version into {@code [major, minor, service, qualifier]}.
     * <p>The build qualifier is a Tycho timestamp (e.g. {@code 202609052012},
     * ~2e11) that overflows {@code int}, so every component is carried as a
     * {@code long}.</p>
     */
    public static long[] parse(String version)
    {
        long[] out = new long[4];
        if (version == null)
        {
            return out;
        }
        String[] parts = version.split("\\."); //$NON-NLS-1$
        for (int i = 0; i < parts.length && i < 4; i++)
        {
            try
            {
                out[i] = Long.parseLong(parts[i].trim());
            }
            catch (NumberFormatException e)
            {
                out[i] = 0;
            }
        }
        return out;
    }

    /** Compares two parsed versions; negative / zero / positive. */
    public static int compare(long[] a, long[] b)
    {
        for (int i = 0; i < 4; i++)
        {
            if (a[i] != b[i])
            {
                return Long.compare(a[i], b[i]);
            }
        }
        return 0;
    }

    /** True when {@code available} is strictly newer than {@code installed}. */
    public static boolean isNewer(String available, String installed)
    {
        return available != null && installed != null
            && compare(parse(available), parse(installed)) > 0;
    }

    /** The running bundle's version, or {@code null} when not resolvable (headless). */
    public static String installedBundleVersion()
    {
        try
        {
            Bundle bundle = FrameworkUtil.getBundle(PluginSelfUpdate.class);
            if (bundle != null && bundle.getVersion() != null)
            {
                return bundle.getVersion().toString();
            }
        }
        catch (Throwable t)
        {
            // Headless / non-OSGi context: not resolvable.
        }
        return null;
    }

    /**
     * Copies {@code jarName} from {@code srcDir} into the pool plugins dir,
     * removing any older same-bundle jar first so no stale duplicate lingers.
     *
     * @return the absolute path of the installed jar
     */
    public static Path installJarIntoPool(String home, Path srcDir, String jarName) throws IOException
    {
        Path pool = poolPluginsDir(home);
        Files.createDirectories(pool);
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(pool, JAR_PREFIX + "*.jar")) //$NON-NLS-1$
        {
            for (Path p : stream)
            {
                if (!p.getFileName().toString().equals(jarName))
                {
                    Files.deleteIfExists(p);
                }
            }
        }
        Path target = pool.resolve(jarName);
        Files.copy(srcDir.resolve(jarName), target, StandardCopyOption.REPLACE_EXISTING);
        return target;
    }

    /** The formatted {@code bundles.info} line for a bundle. */
    public static String bundlesInfoLine(String symbolic, String version, String jarPath)
    {
        return symbolic + "," + version + "," + jarPath + ",4,false"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    /**
     * Replaces the registered line for {@code symbolic} in {@code bundles.info}
     * with the new version and absolute jar path, preserving every other line.
     *
     * @return 1 when a line was replaced, 0 when the bundle is not registered there
     */
    public static int rewriteBundlesInfo(Path file, String symbolic, String version, String jarPath)
        throws IOException
    {
        Path path = file == null ? null : file.toAbsolutePath();
        if (path == null || !Files.isRegularFile(path))
        {
            return 0;
        }
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        String prefix = symbolic + ","; //$NON-NLS-1$
        for (int i = 0; i < lines.size(); i++)
        {
            if (lines.get(i).trim().startsWith(prefix))
            {
                lines.set(i, bundlesInfoLine(symbolic, version, jarPath));
                Files.write(path, lines, StandardCharsets.UTF_8);
                return 1;
            }
        }
        return 0;
    }

    /** Resolves the running EDT's {@code bundles.info}, or {@code null} when unresolvable. */
    public static Path runtimeBundlesInfo()
    {
        try
        {
            URL url = Platform.getInstallLocation().getURL();
            Path install = url == null ? null : installPath(url);
            if (install != null)
            {
                return Paths.get(install.toString(), BUNDLES_INFO_SUBPATH);
            }
        }
        catch (Throwable t)
        {
            // Headless / non-EDT context.
        }
        return null;
    }

    private static Path installPath(URL url)
    {
        try
        {
            return Paths.get(url.toURI());
        }
        catch (Exception e)
        {
            try
            {
                return Paths.get(url.getPath());
            }
            catch (Exception e2)
            {
                return null;
            }
        }
    }
}
