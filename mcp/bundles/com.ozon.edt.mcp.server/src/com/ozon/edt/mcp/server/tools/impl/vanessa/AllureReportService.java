/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 *
 * edt-mcp-vanessa: locate raw Allure results and generate a static report.
 */

package com.ozon.edt.mcp.server.tools.impl.vanessa;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Generates a static, browser-servable Allure report from the raw Allure
 * results a Vanessa Automation BDD run wrote into its out dir.
 *
 * <p>A VA run configured with {@code ДелатьОтчетВФорматеАллюр} leaves a set of
 * {@code *-result.json} / {@code *-container.json} / {@code *-attachment.*}
 * files (typically under {@code out/&lt;proj&gt;/allure/}) — these are NOT a
 * viewable report. This helper turns them into a browsable report by invoking
 * the {@code allure} commandline ({@code allure generate ... --clean}) as a
 * subprocess, mirroring the {@code PluginSelfUpdate} pattern (static, pure,
 * headless-testable; the binary path and JVM are resolved at call time).
 *
 * <p>The Allure CLI needs a writable {@code java.io.tmpdir}, so the subprocess
 * is launched with {@code JAVA_OPTS=-Djava.io.tmpdir=&lt;home&gt;/.1c-tools/allure/tmp}
 * and an explicit {@code JAVA_HOME} (this plugin's own JVM), independent of the
 * GUI-launched EDT's {@code PATH}.
 */
public final class AllureReportService
{
    /** Relative dir under the out dir where VA writes raw Allure results. */
    public static final String ALLURE_RESULTS_DIR = "allure"; //$NON-NLS-1$
    /** Relative dir (sibling of the results) where the report is generated. */
    public static final String REPORT_DIR = "allure-report"; //$NON-NLS-1$
    /** Entry file of a generated Allure report. */
    public static final String INDEX = "index.html"; //$NON-NLS-1$
    /** Suffix of a single Allure result file. */
    private static final String RESULT_SUFFIX = "-result.json"; //$NON-NLS-1$
    /** Local install root for the Allure commandline ({@code $HOME}/...). */
    private static final String HOME_TOOLS_REL = ".1c-tools/allure"; //$NON-NLS-1$
    /** env.sh key that may point at the Allure binary. */
    private static final String KEY_ALLURE_BIN = "ALLURE_BIN"; //$NON-NLS-1$

    private static final long GENERATE_TIMEOUT_SECONDS = 240L;

    private AllureReportService()
    {
        // Static helper only.
    }

    /**
     * Locates the raw Allure results dir beneath an out dir.
     *
     * @param outDir the run's out dir
     * @return the dir holding {@code *-result.json} (prefers {@code outDir/allure}),
     *         or {@code null} when neither the out dir nor its {@code allure}
     *         subdir contains any Allure result
     */
    public static Path findResultsDir(Path outDir)
    {
        if (outDir == null)
        {
            return null;
        }
        Path nested = outDir.resolve(ALLURE_RESULTS_DIR);
        if (isResultsDir(nested))
        {
            return nested;
        }
        if (isResultsDir(outDir))
        {
            return outDir;
        }
        return null;
    }

    /**
     * Best-effort project key from an out dir path: the path segment right after
     * a {@code .../vanessa/out/<proj>} segment, or {@code null}.
     */
    public static String deriveProject(Path outDir)
    {
        java.util.List<String> segs = new java.util.ArrayList<>();
        outDir.toAbsolutePath().normalize().iterator().forEachRemaining(s -> segs.add(s.toString()));
        for (int i = 0; i + 1 < segs.size(); i++)
        {
            if ("out".equals(segs.get(i))) //$NON-NLS-1$
            {
                return segs.get(i + 1);
            }
        }
        return null;
    }

    /** {@code true} when the dir exists and contains at least one {@code *-result.json}. */
    public static boolean isResultsDir(Path dir)
    {
        if (dir == null || !Files.isDirectory(dir))
        {
            return false;
        }
        try (Stream<Path> s = Files.list(dir))
        {
            return s.anyMatch(p -> p.getFileName().toString().endsWith(RESULT_SUFFIX));
        }
        catch (IOException e)
        {
            return false;
        }
    }

    /**
     * Resolves the {@code allure} binary to use for generation, in order:
     * explicit parameter → project {@code env.sh} {@code ALLURE_BIN} → the local
     * {@code ~/.1c-tools/allure} install → {@code PATH}.
     *
     * @param project     project key (may be {@code null}) used to read env.sh
     * @param explicitBin a tool parameter override, or {@code null}
     * @return the {@code allure} executable path, or {@code null} when none is found
     */
    public static Path resolveAllureBin(String project, String explicitBin)
    {
        if (explicitBin != null && !explicitBin.trim().isEmpty())
        {
            Path p = Paths.get(explicitBin.trim());
            if (Files.isRegularFile(p))
            {
                return p.toAbsolutePath();
            }
        }

        if (project != null && !project.trim().isEmpty())
        {
            String fromEnv = VanessaProjectConfig.parseEnvFile(
                VanessaProjectConfig.envPath(project.trim())).get(KEY_ALLURE_BIN);
            if (fromEnv != null && !fromEnv.isEmpty())
            {
                Path p = expandHome(fromEnv);
                if (Files.isRegularFile(p))
                {
                    return p.toAbsolutePath();
                }
            }
        }

        Path localInstall = highestLocalInstall();
        if (localInstall != null)
        {
            return localInstall;
        }

        Path fromPath = findInPath();
        if (fromPath != null)
        {
            return fromPath;
        }

        return null;
    }

    /**
     * Generates the static report for {@code resultsDir} into its sibling
     * {@code allure-report} dir and returns that dir.
     *
     * @param home     user home (for the writable temp dir)
     * @param javaHome JVM home for the subprocess, or {@code null} to use
     *                 {@code System.getProperty("java.home")}
     * @param allureBin the {@code allure} executable
     * @param resultsDir raw Allure results dir
     * @return the generated report dir (with {@code index.html})
     * @throws IOException when the binary is unusable, generation fails, or the
     *                     report has no {@code index.html}
     */
    public static Path generate(String home, String javaHome, Path allureBin, Path resultsDir)
        throws IOException
    {
        return generate(home, javaHome, allureBin, resultsDir, null);
    }

    /**
     * Generates the static report for {@code resultsDir} into {@code reportDirOverride}
     * (or, when {@code null}, into its sibling {@code allure-report} dir) and returns
     * that dir.
     *
     * @param home     user home (for the writable temp dir)
     * @param javaHome JVM home for the subprocess, or {@code null} to use
     *                 {@code System.getProperty("java.home")}
     * @param allureBin the {@code allure} executable
     * @param resultsDir raw Allure results dir
     * @param reportDirOverride target report dir, or {@code null} to auto-detect
     *                          (a sibling of {@code resultsDir})
     * @return the generated report dir (with {@code index.html})
     * @throws IOException when the binary is unusable, generation fails, or the
     *                     report has no {@code index.html}
     */
    public static Path generate(String home, String javaHome, Path allureBin, Path resultsDir,
        Path reportDirOverride) throws IOException
    {
        Path reportDir = reportDirOverride != null
            ? reportDirOverride.toAbsolutePath()
            : resultsDir.getParent().resolve(REPORT_DIR);
        Files.createDirectories(reportDir.getParent());

        Path tmpDir = Paths.get(home, HOME_TOOLS_REL, "tmp"); //$NON-NLS-1$
        Files.createDirectories(tmpDir);

        String theJava = javaHome != null && !javaHome.isEmpty()
            ? javaHome : System.getProperty("java.home"); //$NON-NLS-1$

        ProcessBuilder pb = new ProcessBuilder(
            allureBin.toAbsolutePath().toString(),
            "generate", //$NON-NLS-1$
            resultsDir.toAbsolutePath().toString(),
            "-o", reportDir.toAbsolutePath().toString(), //$NON-NLS-1$
            "--clean"); //$NON-NLS-1$
        pb.environment().put("JAVA_HOME", theJava); //$NON-NLS-1$
        pb.environment().put("JAVA_OPTS", "-Djava.io.tmpdir=" + tmpDir.toAbsolutePath()); //$NON-NLS-1$
        pb.redirectErrorStream(true);

        Process process;
        try
        {
            process = pb.start();
        }
        catch (IOException e)
        {
            throw new IOException("cannot start allure '" + allureBin + "': " + e.getMessage()); //$NON-NLS-1$ //$NON-NLS-2$
        }

        String output;
        boolean finished;
        try
        {
            finished = process.waitFor(GENERATE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new IOException("allure generate interrupted"); //$NON-NLS-1$
        }

        if (!finished)
        {
            process.destroyForcibly();
            throw new IOException("allure generate timed out after " + GENERATE_TIMEOUT_SECONDS + "s"); //$NON-NLS-1$
        }
        if (process.exitValue() != 0)
        {
            throw new IOException("allure generate failed (exit " + process.exitValue() + "): " + tail(output)); //$NON-NLS-1$
        }
        if (!Files.isRegularFile(reportDir.resolve(INDEX)))
        {
            throw new IOException("allure generate finished but " + INDEX + " is missing in " + reportDir); //$NON-NLS-1$
        }
        return reportDir;
    }

    /**
     * Deletes a generated Allure report directory tree (recursively). A missing
     * dir is a no-op, so it is safe to call before the first generation.
     *
     * @param reportDir the report dir to remove
     * @throws IOException when a deletion fails
     */
    public static void clearReport(Path reportDir) throws IOException
    {
        if (reportDir == null || !Files.exists(reportDir))
        {
            return;
        }
        try (Stream<Path> s = Files.walk(reportDir))
        {
            // Delete children before parents: reverse lexicographic order puts
            // deeper paths first.
            for (Path p : s.sorted(java.util.Comparator.reverseOrder()).toList())
            {
                Files.deleteIfExists(p);
            }
        }
    }

    /**
     * Removes the raw Allure result artifacts ({@code *-result.json},
     * {@code *-container.json}, {@code *-attachment.*}, plus the auxiliary
     * {@code executors.json}/{@code categories.json}/{@code environment.properties}
     * and the {@code history} dir) from {@code resultsDir}, so a fresh run's report
     * does not accumulate results from earlier runs. Non-Allure files are left alone.
     *
     * @param resultsDir raw Allure results dir
     * @return the number of removed items (files or dirs)
     * @throws IOException when a deletion fails
     */
    public static int clearResults(Path resultsDir) throws IOException
    {
        if (resultsDir == null || !Files.isDirectory(resultsDir))
        {
            return 0;
        }
        int removed = 0;
        try (Stream<Path> s = Files.list(resultsDir))
        {
            for (Path p : s.toList())
            {
                String name = p.getFileName().toString();
                boolean artifact = name.endsWith(RESULT_SUFFIX)                  //$NON-NLS-1$
                    || name.endsWith("-container.json")                           //$NON-NLS-1$
                    || name.contains("-attachment.")                             //$NON-NLS-1$
                    || name.equals("executors.json")                             //$NON-NLS-1$
                    || name.equals("categories.json")                            //$NON-NLS-1$
                    || name.equals("environment.properties")                     //$NON-NLS-1$
                    || name.equals("history");                                   //$NON-NLS-1$
                if (artifact)
                {
                    if (Files.isDirectory(p))
                    {
                        clearReport(p);
                    }
                    else
                    {
                        Files.deleteIfExists(p);
                    }
                    removed++;
                }
            }
        }
        return removed;
    }

    /** Last (highest) lines of an output string, for error messages. */
    static String tail(String output)
    {
        if (output == null || output.isEmpty())
        {
            return "(no output)"; //$NON-NLS-1$
        }
        String[] lines = output.split("\\R"); //$NON-NLS-1$
        int from = Math.max(0, lines.length - 6);
        return String.join(" | ", java.util.Arrays.copyOfRange(lines, from, lines.length)); //$NON-NLS-1$
    }

    /** Highest-versioned {@code <home>/.1c-tools/allure/allure-&#42;/bin/allure}, or {@code null}. */
    private static Path highestLocalInstall()
    {
        String home = System.getProperty("user.home"); //$NON-NLS-1$
        Path root = Paths.get(home, HOME_TOOLS_REL);
        if (!Files.isDirectory(root))
        {
            return null;
        }
        try (DirectoryStream<Path> s = Files.newDirectoryStream(root))
        {
            Path best = null;
            for (Path d : s)
            {
                if (!Files.isDirectory(d))
                {
                    continue;
                }
                Path bin = d.resolve("bin").resolve("allure"); //$NON-NLS-1$ //$NON-NLS-2$
                if (Files.isRegularFile(bin)
                    && (best == null || d.getFileName().toString().compareTo(best.getFileName().toString()) > 0))
                {
                    best = bin;
                }
            }
            return best;
        }
        catch (IOException e)
        {
            return null;
        }
    }

    /** Scans {@code PATH} for an executable named {@code allure}, or {@code null}. */
    private static Path findInPath()
    {
        String path = System.getenv("PATH"); //$NON-NLS-1$
        if (path == null || path.isEmpty())
        {
            return null;
        }
        for (String dir : path.split(File.pathSeparator))
        {
            if (dir == null || dir.isEmpty())
            {
                continue;
            }
            Path bin = Paths.get(dir, "allure"); //$NON-NLS-1$
            if (Files.isRegularFile(bin) && Files.isExecutable(bin))
            {
                return bin.toAbsolutePath();
            }
        }
        return null;
    }

    private static Path expandHome(String value)
    {
        String home = System.getProperty("user.home"); //$NON-NLS-1$
        return Paths.get(value.replace("$HOME", home).replace("${HOME}", home)); //$NON-NLS-1$ //$NON-NLS-2$
    }
}
