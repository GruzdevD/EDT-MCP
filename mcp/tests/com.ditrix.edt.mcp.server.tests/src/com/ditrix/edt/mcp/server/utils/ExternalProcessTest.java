/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

/**
 * Tests for {@link ExternalProcess}, the bounded external-process runner.
 * <p>
 * Runs real short-lived {@code /bin/sh} subprocesses (present on macOS and CI Linux), so it proves
 * the actual start/wait/drain/timeout/kill machinery rather than a mock of it.
 */
public class ExternalProcessTest
{
    @Test
    public void successReturnsExitZeroAndOutput()
    {
        ExternalProcess.Result r = ExternalProcess.run(
            Arrays.asList("/bin/sh", "-c", "echo hello"), null, 5000);
        assertTrue(r.succeeded());
        assertEquals(0, r.exitCode);
        assertFalse(r.timedOut);
        assertTrue(r.output.contains("hello"));
    }

    @Test
    public void nonZeroExitCodeIsReported()
    {
        ExternalProcess.Result r = ExternalProcess.run(
            Arrays.asList("/bin/sh", "-c", "echo err >&2; exit 3"), null, 5000);
        assertEquals(3, r.exitCode);
        assertFalse(r.succeeded());
        assertFalse(r.timedOut);
        assertTrue(r.output.contains("err"));
    }

    @Test
    public void timeoutKillsTheProcessAndFlagsIt()
    {
        long start = System.nanoTime();
        ExternalProcess.Result r = ExternalProcess.run(
            Arrays.asList("/bin/sh", "-c", "sleep 10"), null, 400);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertTrue("expected a timeout result", r.timedOut);
        assertEquals(-1, r.exitCode);
        assertFalse(r.succeeded());
        // must return long before the sleep finishes, and not hang the caller
        assertTrue("killed run was too slow: " + elapsedMs + "ms", elapsedMs < 9000);
    }

    @Test
    public void failedToStartIsReportedForMissingExecutable()
    {
        ExternalProcess.Result r = ExternalProcess.run(
            Collections.singletonList("/definitely/not/here/1cv8"), null, 1000);
        assertTrue(r.failedToStart);
        assertFalse(r.succeeded());
    }

    @Test
    public void emptyArgvIsRefused()
    {
        ExternalProcess.Result r = ExternalProcess.run(List.of(), null, 1000);
        assertTrue(r.failedToStart);
        assertFalse(r.succeeded());
    }

    @Test
    public void largeOutputIsCappedWithoutDeadlocking()
    {
        ExternalProcess.Result r = ExternalProcess.run(
            Arrays.asList("/bin/sh", "-c", "yes x | head -c 1000000"), null, 5000);
        assertTrue(r.succeeded());
        assertTrue("output should not exceed the cap", r.output.length() <= 300_000);
    }
}
