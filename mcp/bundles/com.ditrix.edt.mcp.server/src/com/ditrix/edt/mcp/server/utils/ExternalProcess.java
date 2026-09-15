/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import com.ditrix.edt.mcp.server.Activator;

/**
 * Runs an external command-line process with a bounded deadline, draining its combined output on a
 * separate thread (so a large or unending output can never deadlock the wait), capturing a capped
 * transcript, and killing the whole descendant tree when the deadline is reached.
 *
 * <p>The single source of truth for shelling OUT of the EDT plugin (a {@code 1cv8 DESIGNER} update,
 * a future CLI tool). Modeled on {@code GitTool.runGit}, which is git-specific and lives in a tool;
 * this is the generically reusable form (CLAUDE.md rule #5: shared helpers live in {@code utils/}).
 * Unlike {@code GitTool}, the result is a plain value object the caller formats into its own
 * message, not a {@code ToolResult} payload.
 *
 * <p>Headless-testable: the method has no EDT dependency on its happy path; {@link Activator} is
 * only used for logging, guarded against a {@code null} activator (a standalone JUnit run).
 */
public final class ExternalProcess
{
    /** Default cap on the captured output, in bytes. */
    private static final int MAX_OUTPUT_BYTES = 200_000;

    /** How long to wait for the drain thread to finish after the process exits. */
    private static final long DRAIN_JOIN_MILLIS = 2_000L;

    /** Grace period for killing a process tree, also used after the process ended. */
    private static final long KILL_GRACE_MILLIS = 5_000L;

    private ExternalProcess()
    {
        // Utility class
    }

    /**
     * The bounded outcome of a {@link ExternalProcess#run} call.
     *
     * <p>The fields are deliberately low-level: the caller decides which combination (a non-zero
     * exit, a time-out, a failed start) means what for its own operation.
     */
    public static final class Result
    {
        /** The process exit code, or {@code -1} when the process never produced one. */
        public final int exitCode;
        /** {@code true} when the process was killed because it exceeded the deadline. */
        public final boolean timedOut;
        /** {@code true} when the calling thread was interrupted while waiting. */
        public final boolean interrupted;
        /** {@code true} when the executable could not be started at all (IO {@code IOException}). */
        public final boolean failedToStart;
        /** {@code true} when the captured transcript was truncated at {@link #MAX_OUTPUT_BYTES}. */
        public final boolean truncated;
        /** The combined (stdout+stderr) output, capped and UTF-8 decoded. */
        public final String output;
        /** A human description of a start failure, or {@code null}. */
        public final String error;

        Result(int exitCode, boolean timedOut, boolean interrupted, boolean failedToStart,
            boolean truncated, String output, String error)
        {
            this.exitCode = exitCode;
            this.timedOut = timedOut;
            this.interrupted = interrupted;
            this.failedToStart = failedToStart;
            this.truncated = truncated;
            this.output = output;
            this.error = error;
        }

        /** @return {@code true} when the process ran and exited {@code 0} before the deadline. */
        public boolean succeeded()
        {
            return !timedOut && !interrupted && !failedToStart && exitCode == 0;
        }
    }

    /**
     * Runs {@code argv} with a bounded deadline, capturing its combined output.
     *
     * @param argv the complete command line (executable first); never {@code null} or empty
     * @param workDir the working directory, or {@code null} to inherit the plugin's
     * @param timeoutMillis the deadline before which the process must exit, or {@code <= 0} for none
     * @return the bounded result, never {@code null}
     */
    public static Result run(List<String> argv, File workDir, long timeoutMillis)
    {
        Objects.requireNonNull(argv, "argv");
        if (argv.isEmpty())
        {
            return new Result(-1, false, false, true, false, "", "empty command line"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        String displayName = String.join(" ", argv); //$NON-NLS-1$
        ProcessBuilder builder = new ProcessBuilder(argv);
        if (workDir != null)
        {
            builder.directory(workDir);
        }
        builder.redirectErrorStream(true);

        long deadlineNanos = timeoutMillis > 0
            ? System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis) : Long.MAX_VALUE;

        StringBuilder out = new StringBuilder();
        boolean[] truncated = {false};
        Process process = null;
        Thread drain = null;
        try
        {
            process = builder.start();
            process.getOutputStream().close(); // no stdin
            final Process started = process;
            drain = new Thread(() -> drain(started, out, truncated), "external-process-drain"); //$NON-NLS-1$
            drain.setDaemon(true);
            drain.start();

            if (!awaitExit(process, deadlineNanos))
            {
                killTree(process, deadlineNanos);
                closeQuietly(process.getInputStream()); // unblock a drain a survivor still feeds
                joinDrain(drain);
                return new Result(-1, true, false, false, truncated[0], out.toString(), null);
            }
            joinDrain(drain);
            int exitCode = process.exitValue();
            return new Result(exitCode, false, false, false, truncated[0], out.toString(), null);
        }
        catch (IOException e)
        {
            Activator.logError("external process: failed to start '" + displayName //$NON-NLS-1$
                + "'", e); //$NON-NLS-1$
            return new Result(-1, false, false, true, truncated[0], out.toString(),
                e.getMessage());
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt(); // restore the interrupt flag
            if (process != null && process.isAlive())
            {
                killTree(process, deadlineNanos);
            }
            return new Result(-1, false, true, false, truncated[0], out.toString(), null);
        }
        finally
        {
            if (process != null)
            {
                if (process.isAlive())
                {
                    killTree(process, deadlineNanos);
                }
                if (drain != null && drain.isAlive())
                {
                    // Something still holds the write end; closing our read end ends the drain.
                    closeQuietly(process.getInputStream());
                }
            }
        }
    }

    /**
     * Reads the process's combined stream until EOF, appending up to {@link #MAX_OUTPUT_BYTES}.
     *
     * @param process the running process (never {@code null})
     * @param out the transcript buffer
     * @param truncated whether the cap was reached
     */
    private static void drain(Process process, StringBuilder out, boolean[] truncated)
    {
        try (BufferedReader reader = new BufferedReader(
            new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8)))
        {
            char[] buf = new char[8192];
            int len;
            while ((len = reader.read(buf)) != -1)
            {
                if (!truncated[0] && out.length() + len <= MAX_OUTPUT_BYTES)
                {
                    out.append(buf, 0, len);
                }
                else if (!truncated[0])
                {
                    int room = MAX_OUTPUT_BYTES - out.length();
                    if (room > 0)
                    {
                        out.append(buf, 0, room);
                    }
                    truncated[0] = true;
                }
            }
        }
        catch (IOException e)
        {
            Activator.logError("external process: draining output failed", e); //$NON-NLS-1$
        }
    }

    /**
     * Waits, bounded by {@code deadlineNanos}, for the process to exit. {@code true} on exit.
     *
     * @param process the running process (never {@code null})
     * @param deadlineNanos the deadline in {@link System#nanoTime()} terms, or {@code Long.MAX_VALUE}
     * @return {@code true} when the process exited; {@code false} when the deadline passed first
     * @throws InterruptedException when the waiting thread is interrupted
     */
    private static boolean awaitExit(Process process, long deadlineNanos) throws InterruptedException
    {
        long remaining = deadlineNanos - System.nanoTime();
        if (remaining <= 0)
        {
            return false;
        }
        return process.waitFor(remaining, TimeUnit.NANOSECONDS);
    }

    /** {@code destroyForcibly()} on the process and its current descendants, then awaits them. */
    private static void killTree(Process process, long deadlineNanos)
    {
        List<ProcessHandle> handles = new ArrayList<>();
        process.descendants().forEach(handles::add); // snapshot while still a parent
        if (process.isAlive())
        {
            process.destroyForcibly();
        }
        handles.add(process.toHandle());
        long killDeadline = Math.min(deadlineNanos,
            System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(KILL_GRACE_MILLIS));
        for (ProcessHandle handle : handles)
        {
            try
            {
                if (handle.isAlive())
                {
                    handle.destroyForcibly();
                }
            }
            catch (RuntimeException e) // NOSONAR cleanup must never replace the result
            {
                Activator.logError("external process: killing a child failed", e); //$NON-NLS-1$
            }
        }
        // destroyForcibly() is ASYNCHRONOUS: bounded by one shared grace period, poll for the
        // handles to drop (ProcessHandle has no blocking waitFor - this is the JDK's own idiom).
        for (ProcessHandle handle : handles)
        {
            try
            {
                while (handle.isAlive() && System.nanoTime() < killDeadline)
                {
                    Thread.sleep(10L);
                }
            }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /** Joins the drain thread, bounded, so a survivor still feeding the pipe cannot hang the caller. */
    private static void joinDrain(Thread drain)
    {
        if (drain != null && drain.isAlive())
        {
            try
            {
                drain.join(DRAIN_JOIN_MILLIS);
            }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** Closes a stream, ignoring any failure - used only to unblock a reader. */
    private static void closeQuietly(java.io.Closeable stream)
    {
        try
        {
            stream.close();
        }
        catch (IOException e) // NOSONAR closing is best-effort: the reader is what matters
        {
            Activator.logError("external process: closing the output pipe failed", e); //$NON-NLS-1$
        }
    }
}
