/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.junit.Test;

/**
 * Tests for {@link ExternalProcess}, the bounded external-process runner.
 * <p>
 * The {@link ExternalProcess#run} tests execute real short-lived {@code /bin/sh} subprocesses
 * (present on macOS and CI Linux), so they prove the actual start/wait/drain/timeout/kill
 * machinery rather than a mock of it. The {@link ExternalProcess#await} and
 * {@link ExternalProcess#destroyTree} tests use mocks to drive pipe draining, descendant
 * destruction, and interruption deterministically.
 */
public class ExternalProcessTest
{
    /** A mocked {@link Process} with empty pipes, ready for stubbing in individual tests. */
    private Process process() throws Exception
    {
        Process process = mock(Process.class);
        when(process.getInputStream()).thenReturn(new ByteArrayInputStream(new byte[0]));
        when(process.getErrorStream()).thenReturn(new ByteArrayInputStream(new byte[0]));
        when(process.getOutputStream()).thenReturn(new ByteArrayOutputStream());
        return process;
    }

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

    @Test(timeout = 10000)
    public void bothPipesAreDrainedBeforeWaitCompletes() throws Exception
    {
        Process process = process();
        PipedInputStream out = new PipedInputStream();
        PipedInputStream err = new PipedInputStream();
        PipedOutputStream outWriter = new PipedOutputStream(out);
        PipedOutputStream errWriter = new PipedOutputStream(err);
        when(process.getInputStream()).thenReturn(out);
        when(process.getErrorStream()).thenReturn(err);
        byte[] bytes = new byte[128 * 1024];
        when(process.waitFor(5000, TimeUnit.MILLISECONDS)).thenAnswer(call -> {
            outWriter.write(bytes);
            errWriter.write(bytes);
            outWriter.close();
            errWriter.close();
            return true;
        });
        when(process.exitValue()).thenReturn(3);
        var result = ExternalProcess.await(process, 5000, child -> child.getOutputStream().close());
        assertEquals(3, result.exitCode());
        assertFalse(result.timedOut());
        assertArrayEquals(bytes, result.stdout());
        assertArrayEquals(bytes, result.stderr());
    }

    @Test
    public void timeoutDestroysDescendantsAndParent() throws Exception
    {
        Process process = process();
        ProcessHandle child = mock(ProcessHandle.class);
        when(process.descendants()).thenReturn(Stream.of(child));
        var result = ExternalProcess.await(process, 1, p -> p.getOutputStream().close());
        assertTrue(result.timedOut());
        assertEquals(-1, result.exitCode());
        verify(child).destroyForcibly();
        verify(process).destroyForcibly();
    }

    @Test
    public void interruptionKillsTreeAndRestoresInterrupt() throws Exception
    {
        Process process = process();
        ProcessHandle child = mock(ProcessHandle.class);
        when(process.descendants()).thenReturn(Stream.of(child));
        when(process.waitFor(5, TimeUnit.MILLISECONDS)).thenThrow(new InterruptedException());
        try
        {
            ExternalProcess.await(process, 5, p -> p.getOutputStream().close());
            fail("Expected interruption");
        }
        catch (InterruptedException e)
        {
            assertTrue(Thread.currentThread().isInterrupted());
            verify(child).destroyForcibly();
            verify(process).destroyForcibly();
        }
        finally { Thread.interrupted(); }
    }

    @Test
    public void refusedDescendantKillStillKillsTheParent() throws Exception
    {
        Process process = process();
        ProcessHandle child = mock(ProcessHandle.class);
        when(process.descendants()).thenReturn(Stream.of(child));
        when(child.destroyForcibly()).thenThrow(new IllegalStateException("already inaccessible"));
        ExternalProcess.destroyTree(process);
        verify(process).destroyForcibly();
    }

    @Test
    public void processHandleLaunchesUseTheSameTreeCleanup()
    {
        ProcessHandle process = mock(ProcessHandle.class);
        ProcessHandle child = mock(ProcessHandle.class);
        when(process.descendants()).thenReturn(Stream.of(child));
        when(process.onExit()).thenReturn(java.util.concurrent.CompletableFuture.completedFuture(process));
        ExternalProcess.destroyTree(process);
        var order = inOrder(child, process);
        order.verify(child).destroyForcibly();
        order.verify(process).destroyForcibly();
    }

}
