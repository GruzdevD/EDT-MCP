/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils.snapshot;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.WString;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.win32.StdCallLibrary;

/** Owns a Windows child process and its descendants from before the first instruction. */
final class WindowsJobProcess implements AutoCloseable
{
    private static final int JOB_BASIC_ACCOUNTING = 1;
    private static final int JOB_EXTENDED_LIMITS = 9;
    private static final int KILL_ON_JOB_CLOSE = 0x2000;
    private static final int CREATE_UNICODE_ENVIRONMENT = 0x400;
    private static final int EXTENDED_STARTUPINFO_PRESENT = 0x00080000;
    private static final int CREATE_NO_WINDOW = 0x08000000;
    private static final long PROC_THREAD_ATTRIBUTE_JOB_LIST = 0x0002000D;
    private static final int WAIT_OBJECT_0 = 0;
    private static final int WAIT_TIMEOUT = 258;
    private static final int STARTUP_INFORMATION_SIZE = 104; // Win64 STARTUPINFOW
    private static final int STARTUP_INFORMATION_EX_SIZE = STARTUP_INFORMATION_SIZE + 8; // Attribute list pointer
    private static final int EXTENDED_LIMITS_SIZE = 144; // Win64 JOBOBJECT_EXTENDED_LIMIT_INFORMATION
    private static final int BASIC_ACCOUNTING_SIZE = 48; // JOBOBJECT_BASIC_ACCOUNTING_INFORMATION

    private final Kernel kernel;
    private final Pointer job;
    private Pointer process;

    private WindowsJobProcess(Kernel kernel, Pointer job)
    {
        this.kernel = kernel;
        this.job = job;
    }

    static WindowsJobProcess start(Path executable, List<String> arguments, Path workingDirectory,
        Map<String, String> environment) throws IOException
    {
        if (Native.POINTER_SIZE != 8)
        {
            throw failed();
        }
        Kernel kernel;
        try
        {
            kernel = Native.load("kernel32", Kernel.class);
        }
        catch (RuntimeException | LinkageError e)
        {
            throw failed();
        }
        return start(kernel, executable, arguments, workingDirectory, environment);
    }

    static WindowsJobProcess start(Kernel kernel, Path executable, List<String> arguments, Path workingDirectory,
        Map<String, String> environment) throws IOException
    {
        Pointer job = kernel.CreateJobObjectW(null, null);
        if (job == null)
        {
            throw failed();
        }
        WindowsJobProcess result = new WindowsJobProcess(kernel, job);
        try (Memory limits = new Memory(EXTENDED_LIMITS_SIZE))
        {
            limits.clear();
            limits.setInt(16, KILL_ON_JOB_CLOSE); // BasicLimitInformation.LimitFlags
            if (!kernel.SetInformationJobObject(job, JOB_EXTENDED_LIMITS, limits, EXTENDED_LIMITS_SIZE))
            {
                throw failed();
            }
            result.launch(executable, arguments, workingDirectory, environment);
            return result;
        }
        catch (IOException | RuntimeException | LinkageError e)
        {
            result.close();
            throw failed();
        }
    }

    private void launch(Path executable, List<String> arguments, Path workingDirectory,
        Map<String, String> environment) throws IOException
    {
        List<String> words = new ArrayList<>();
        words.add(executable.toString());
        words.addAll(arguments);
        String command = String.join(" ", words.stream().map(WindowsJobProcess::quote).toList());
        StringBuilder block = new StringBuilder();
        environment.forEach((name, value) -> block.append(name).append('=').append(value).append('\0'));
        block.append('\0');
        byte[] environmentBytes = block.toString().getBytes(StandardCharsets.UTF_16LE);
        try (Memory commandBuffer = new Memory((command.length() + 1L) * 2);
            Memory envBuffer = new Memory(environmentBytes.length);
            Memory startup = new Memory(STARTUP_INFORMATION_EX_SIZE);
            Memory processInfo = new Memory(2L * Native.POINTER_SIZE + 8))
        {
            commandBuffer.setWideString(0, command);
            envBuffer.write(0, environmentBytes, 0, environmentBytes.length);
            try (Memory listSize = new Memory(Native.POINTER_SIZE))
            {
                // The first call reports the required buffer size and returns FALSE by design.
                listSize.clear();
                kernel.InitializeProcThreadAttributeList(null, 1, 0, listSize);
                long required = listSize.getLong(0);
                if (required <= 0)
                {
                    throw failed();
                }
                try (Memory list = new Memory(required); Memory handles = new Memory(Native.POINTER_SIZE))
                {
                    if (!kernel.InitializeProcThreadAttributeList(list, 1, 0, listSize))
                    {
                        throw failed();
                    }
                    try
                    {
                        handles.setPointer(0, job);
                        if (!kernel.UpdateProcThreadAttribute(list, 0, PROC_THREAD_ATTRIBUTE_JOB_LIST,
                            handles, Native.POINTER_SIZE, null, null))
                        {
                            throw failed();
                        }
                        startup.clear();
                        startup.setInt(0, STARTUP_INFORMATION_EX_SIZE);
                        startup.setPointer(STARTUP_INFORMATION_SIZE, list);
                        // Windows assigns the child to the kill-on-close job as part of creation.
                        if (!kernel.CreateProcessW(new WString(executable.toString()), commandBuffer, null, null,
                            false, EXTENDED_STARTUPINFO_PRESENT | CREATE_UNICODE_ENVIRONMENT | CREATE_NO_WINDOW,
                            envBuffer, new WString(workingDirectory.toString()), startup, processInfo))
                        {
                            throw failed();
                        }
                        process = processInfo.getPointer(0);
                        kernel.CloseHandle(processInfo.getPointer(Native.POINTER_SIZE));
                    }
                    finally
                    {
                        kernel.DeleteProcThreadAttributeList(list);
                    }
                }
            }
        }
    }

    boolean finished() throws IOException
    {
        int waited = kernel.WaitForSingleObject(process, 200);
        if (waited == WAIT_TIMEOUT)
        {
            return false;
        }
        if (waited != WAIT_OBJECT_0)
        {
            throw failed();
        }
        try (Memory accounting = new Memory(BASIC_ACCOUNTING_SIZE))
        {
            if (activeProcesses(accounting) == 0)
            {
                return true;
            }
            kernel.Sleep(25);
            return false;
        }
    }

    int exitCode() throws IOException
    {
        IntByReference result = new IntByReference();
        if (!kernel.GetExitCodeProcess(process, result))
        {
            throw failed();
        }
        return result.getValue();
    }

    private int activeProcesses(Memory accounting) throws IOException
    {
        if (!kernel.QueryInformationJobObject(job, JOB_BASIC_ACCOUNTING, accounting, BASIC_ACCOUNTING_SIZE, null))
        {
            throw failed();
        }
        return accounting.getInt(40); // JOBOBJECT_BASIC_ACCOUNTING_INFORMATION.ActiveProcesses
    }

    @Override
    public void close() throws IOException
    {
        try (Memory accounting = new Memory(BASIC_ACCOUNTING_SIZE))
        {
            if (activeProcesses(accounting) == 0)
            {
                return;
            }
            if (!kernel.TerminateJobObject(job, 1))
            {
                throw failed();
            }
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (activeProcesses(accounting) != 0)
            {
                if (System.nanoTime() >= deadline)
                {
                    throw failed();
                }
                // Native wait works while the Java worker has its cancellation interrupt set.
                kernel.Sleep(25);
            }
        }
        finally
        {
            if (process != null)
            {
                kernel.CloseHandle(process);
            }
            kernel.CloseHandle(job);
        }
    }

    static String quote(String word)
    {
        if (!word.isEmpty() && word.chars().noneMatch(ch -> ch == ' ' || ch == '\t' || ch == '"'))
        {
            return word;
        }
        StringBuilder output = new StringBuilder("\"");
        int slashes = 0;
        for (int index = 0; index < word.length(); index++)
        {
            char next = word.charAt(index);
            if (next == '\\')
            {
                slashes++;
                continue;
            }
            output.append("\\".repeat(next == '"' ? slashes * 2 + 1 : slashes)).append(next);
            slashes = 0;
        }
        return output.append("\\".repeat(slashes * 2)).append('"').toString();
    }

    private static IOException failed()
    {
        return new IOException("Could not control the isolated Windows process tree.");
    }

    public interface Kernel extends StdCallLibrary
    {
        Pointer CreateJobObjectW(Pointer attributes, WString name);
        boolean SetInformationJobObject(Pointer job, int informationClass, Pointer information, int length);
        boolean QueryInformationJobObject(Pointer job, int informationClass, Pointer information, int length,
            IntByReference actualLength);
        boolean InitializeProcThreadAttributeList(Pointer list, int count, int flags, Pointer size);
        boolean UpdateProcThreadAttribute(Pointer list, int flags, long attribute, Pointer value, long size,
            Pointer previousValue, Pointer returnSize);
        void DeleteProcThreadAttributeList(Pointer list);
        boolean TerminateJobObject(Pointer job, int exitCode);
        boolean CreateProcessW(WString application, Pointer command, Pointer processAttributes,
            Pointer threadAttributes, boolean inheritHandles, int flags, Pointer environment,
            WString directory, Pointer startupInfo, Pointer processInformation);
        int WaitForSingleObject(Pointer handle, int milliseconds);
        boolean GetExitCodeProcess(Pointer process, IntByReference exitCode);
        void Sleep(int milliseconds);
        boolean CloseHandle(Pointer handle);
    }
}
