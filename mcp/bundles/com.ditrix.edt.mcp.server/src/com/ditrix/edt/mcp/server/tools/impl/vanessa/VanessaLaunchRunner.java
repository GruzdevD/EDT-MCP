/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 *
 * edt-mcp-vanessa: track Vanessa Automation BDD runs by their terminal artifacts.
 */

package com.ditrix.edt.mcp.server.tools.impl.vanessa;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Tracks Vanessa Automation BDD runs launched through the (heavier) EDT launch
 * path — the same {@code debug_launch} flow {@code run-edt.sh} uses. The actual
 * 1cv8 process is started and owned by the EDT launch/VA_Runner machinery; this
 * class only holds a per-run handle so the control/status tools can answer
 * "is it done yet, and did it pass".
 *
 * <p>Unlike {@code IProcess}-exit-based tracking, a Vanessa run's terminal state
 * is judged from the files VA writes into the project out dir (the same files
 * {@code run-edt.sh} polls): a numeric {@code BDDStatus.log} means the run
 * finished — {@code 0} is a pass, any other value a fail — and
 * {@code junit/junit.xml} holds the detailed report. While the status file is
 * absent or not yet numeric, the run is considered {@code running}.
 *
 * <p>Handles live only while EDT is up; after an EDT restart they are gone, so
 * the client re-runs a feature to get a fresh launchId.
 */
public final class VanessaLaunchRunner
{
    /** Terminal VA status/log file names, relative to the out dir. */
    static final String STATUS_FILE = VanessaRunArtifacts.STATUS_FILE;
    static final String LOG_FILE = VanessaRunArtifacts.LOG_FILE;
    static final String JUNIT_SUBPATH = "junit/" + VanessaRunArtifacts.JUNIT_FILE; //$NON-NLS-1$

    /**
     * Parses a launchId handed to a tool. The schema asks for an INTEGER, but the tool is given a
     * string, and the number reached that string as {@code Double.toString()} of the JSON number
     * (the same fact {@code JsonUtils.extractIntArgument} documents and handles): a client that
     * correctly sends {@code "launchId": 1} delivers {@code "1.0"} here. So both {@code "1"} and
     * {@code "1.0"} resolve to 1; a fractional value, a non-number, or anything outside
     * {@code long} is refused rather than guessed.
     *
     * @param raw the caller's text
     * @return the id, or {@code null} when the text does not name one
     */
    public static Long parseLaunchId(String raw)
    {
        if (raw == null)
        {
            return null;
        }
        String text = raw.trim();
        try
        {
            // The trustworthy spelling: exact digits, any magnitude a long can hold.
            return Long.valueOf(Long.parseLong(text));
        }
        catch (NumberFormatException notPlainDigits)
        {
            // Not an integer literal — it may still be the double-shaped rendering of one ("1.0").
        }
        try
        {
            double d = Double.parseDouble(text);
            if (Double.isInfinite(d) || d != Math.floor(d))
            {
                return null; // fractional or beyond double's range — refused
            }
            if (d < Long.MIN_VALUE || d > Long.MAX_VALUE)
            {
                return null; // beyond long — refused
            }
            return Long.valueOf((long) d);
        }
        catch (NumberFormatException notANumber)
        {
            return null;
        }
    }

    /** State of a tracked BDD run. */
    public enum State
    {
        /** Not yet seen a numeric BDDStatus.log. */
        RUNNING,
        /** BDDStatus.log ended with 0 (or junit exists without a failure status). */
        PASSED,
        /** BDDStatus.log ended non-zero, or the launch was rejected. */
        FAILED
    }

    /** A single tracked BDD run. */
    public static final class RunHandle
    {
        public final long id;
        public final String project;
        /** Feature file or directory the run targets. */
        public final String feature;
        /** Absolute out directory where VA writes logs/junit/allure. */
        public final String outDir;
        /** Absolute path to the junit.xml expected for this run. */
        public final String junitReportPath;
        /** Absolute path to the BDDStatus.log consulted for completion. */
        public final String statusPath;
        public final long startMillis;

        /** Non-null when the launch itself was rejected before any run began. */
        public volatile String launchError;

        RunHandle(long id, String project, String feature, String outDir,
            String junitReportPath, String statusPath)
        {
            this.id = id;
            this.project = project;
            this.feature = feature;
            this.outDir = outDir;
            this.junitReportPath = junitReportPath;
            this.statusPath = statusPath;
            this.startMillis = System.currentTimeMillis();
        }

        /** Current file-derived state. */
        public State state()
        {
            if (launchError != null)
            {
                return State.FAILED;
            }
            String status = readStatus(statusPath);
            if (status == null || status.isEmpty())
            {
                return State.RUNNING;
            }
            try
            {
                return Integer.parseInt(status) == 0 ? State.PASSED : State.FAILED;
            }
            catch (NumberFormatException e)
            {
                // Partially-written / non-numeric status: not a terminal verdict yet.
                return State.RUNNING;
            }
        }

        /**
         * Reads the terminal status file content, or {@code null} when absent.
         */
        static String readStatus(String statusPath)
        {
            if (statusPath == null || statusPath.isEmpty())
            {
                return null;
            }
            try
            {
                Path p = Paths.get(statusPath);
                if (!Files.exists(p))
                {
                    return null;
                }
                String text = Files.readString(p, StandardCharsets.UTF_8);
                // VA writes BDDStatus.log with a UTF-8 BOM (U+FEFF); trim() does not remove it and
                // Integer.parseInt would then refuse the numeric verdict, leaving the run "running"
                // forever even though it finished. Strip a leading BOM before trimming.
                if (!text.isEmpty() && text.codePointAt(0) == 0xFEFF)
                {
                    text = text.substring(1);
                }
                return text.trim();
            }
            catch (IOException e)
            {
                return null;
            }
        }
    }

    /** Singleton used by all Vanessa tools. */
    public static final VanessaLaunchRunner INSTANCE = new VanessaLaunchRunner();

    private final AtomicLong nextId = new AtomicLong(1);
    /** Guarded by its own monitor (access is confined to this class). */
    private final Map<Long, RunHandle> runs = new LinkedHashMap<>();

    private VanessaLaunchRunner()
    {
        // Singleton
    }

    /**
     * Builds a new run handle for {@code <outDir>/junit/junit.xml} and registers it.
     *
     * @param project   project key, e.g. {@code afm}
     * @param feature   feature file or directory targeted by the run
     * @param outDir    absolute out directory (from VanessaRunArtifacts.Result)
     * @return the registered handle
     */
    public RunHandle register(String project, String feature, String outDir)
    {
        String junit = outDir + "/" + JUNIT_SUBPATH; //$NON-NLS-1$
        String status = outDir + "/" + STATUS_FILE; //$NON-NLS-1$
        RunHandle handle = new RunHandle(nextId.getAndIncrement(), project, feature,
            outDir, junit, status);
        synchronized (runs)
        {
            runs.put(handle.id, handle);
        }
        return handle;
    }

    /**
     * Looks up a previously launched run, or {@code null} when the id is unknown.
     */
    public RunHandle get(long launchId)
    {
        synchronized (runs)
        {
            return runs.get(Long.valueOf(launchId));
        }
    }
}
