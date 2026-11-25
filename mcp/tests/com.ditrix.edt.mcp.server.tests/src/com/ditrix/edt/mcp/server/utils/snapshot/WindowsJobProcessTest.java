/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.utils.snapshot;

import static org.junit.Assert.assertEquals;
import static org.junit.Assume.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;

import com.ditrix.edt.mcp.server.utils.snapshot.WindowsJobProcess.Kernel;
import com.sun.jna.Native;
import com.sun.jna.Pointer;

public class WindowsJobProcessTest
{
    @Test
    public void processCreationReceivesTheJobHandleInExtendedStartupInfo() throws Exception
    {
        assumeTrue(System.getProperty("os.name").startsWith("Windows") && Native.POINTER_SIZE == 8);
        Kernel kernel = mock(Kernel.class);
        Pointer job = new Pointer(100);
        Pointer process = new Pointer(200);
        Pointer thread = new Pointer(300);
        AtomicReference<Pointer> attributes = new AtomicReference<>();
        when(kernel.CreateJobObjectW(null, null)).thenReturn(job);
        when(kernel.SetInformationJobObject(eq(job), eq(9), any(), eq(144))).thenAnswer(call -> {
            Pointer limits = call.getArgument(2);
            assertEquals(0x2000, limits.getInt(16));
            return true;
        });
        when(kernel.InitializeProcThreadAttributeList(any(), eq(1), eq(0), any())).thenAnswer(call -> {
            Pointer size = call.getArgument(3);
            size.setLong(0, 64L);
            return call.getArgument(0) != null;
        });
        when(kernel.UpdateProcThreadAttribute(any(), eq(0), eq(0x0002000DL), any(), eq(8L), any(), any()))
            .thenAnswer(call -> {
                attributes.set(call.getArgument(0));
                Pointer handles = call.getArgument(3);
                assertEquals(job, handles.getPointer(0));
                return true;
            });
        when(kernel.CreateProcessW(any(), any(), any(), any(), anyBoolean(), anyInt(), any(), any(), any(), any()))
            .thenAnswer(call -> {
                assertEquals(Boolean.FALSE, call.getArgument(4));
                int flags = call.getArgument(5);
                assertEquals(0x08080400, flags);
                Pointer info = call.getArgument(8);
                assertEquals(112, info.getInt(0));
                assertEquals(attributes.get(), info.getPointer(104));
                Pointer result = call.getArgument(9);
                result.setPointer(0, process);
                result.setPointer(8, thread);
                return true;
            });
        when(kernel.QueryInformationJobObject(eq(job), eq(1), any(), eq(48), any())).thenAnswer(call -> {
            Pointer accounting = call.getArgument(2);
            accounting.setInt(40, 0);
            return true;
        });

        Path directory = Path.of("C:\\TestWorkspace");
        try (var child = WindowsJobProcess.start(kernel, directory.resolve("example.exe"),
            List.of(), directory, Map.of("SystemRoot", "C:\\Windows")))
        {
            var sequence = inOrder(kernel);
            sequence.verify(kernel).SetInformationJobObject(eq(job), eq(9), any(), eq(144));
            sequence.verify(kernel).UpdateProcThreadAttribute(any(), eq(0), eq(0x0002000DL), any(), eq(8L),
                any(), any());
            sequence.verify(kernel).CreateProcessW(any(), any(), any(), any(), eq(false), eq(0x08080400),
                any(), any(), any(), any());
            sequence.verify(kernel).CloseHandle(thread);
            sequence.verify(kernel).DeleteProcThreadAttributeList(any());
        }
    }
}
