/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils.snapshot;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class WindowsSourceTreeTest
{
    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Before
    public void requireWindows()
    {
        assumeTrue(System.getProperty("os.name").startsWith("Windows"));
    }

    @Test
    public void readsAndEnumeratesARegularLocalSourceByHandle() throws IOException
    {
        Path root = temp.newFolder("project").toPath();
        Path sources = Files.createDirectory(root.resolve("src"));
        byte[] bytes = "Procedure Test()\r\nEndProcedure\r\n".getBytes(StandardCharsets.UTF_8);
        Files.write(sources.resolve("Модуль.bsl"), bytes);
        try (WindowsSourceTree.Directory project = WindowsSourceTree.load().openProject(root.toString());
            WindowsSourceTree.Directory src = project.child("src"))
        {
            assertEquals(List.of(new WindowsSourceTree.Entry("Модуль.bsl", false, false)), src.entries(10));
            assertArrayEquals(bytes, src.read("Модуль.bsl", 1024));
            assertThrows(IOException.class, () -> src.read("Модуль.bsl", 1));
        }
    }

    @Test
    public void refusesExternalSymlinkBeforeReading() throws IOException
    {
        Path root = temp.newFolder("project").toPath();
        Path external = Files.writeString(temp.newFolder("outside").toPath().resolve("private.bsl"), "outside sentinel");
        // Run on a Windows stand with symlink creation enabled. A fixture failure is not a passing test.
        Path link = Files.createSymbolicLink(root.resolve("link.bsl"), external);
        try (WindowsSourceTree.Directory project = WindowsSourceTree.load().openProject(root.toString()))
        {
            assertSanitized(assertThrows(IOException.class, () -> project.read("link.bsl", 1024)), external);
        }
        finally
        {
            Files.delete(link);
        }
    }

    @Test
    public void refusesJunctionInsertedAfterOpeningTheProject() throws Exception
    {
        Path root = temp.newFolder("project").toPath();
        Path folder = Files.createDirectory(root.resolve("module"));
        Path external = temp.newFolder("outside").toPath();
        Files.writeString(external.resolve("private.bsl"), "outside sentinel");
        try (WindowsSourceTree.Directory project = WindowsSourceTree.load().openProject(root.toString()))
        {
            assertEquals(List.of(new WindowsSourceTree.Entry("module", true, false)), project.entries(10));
            // Remove the empty child while keeping the enumerated project handle open.
            Files.delete(folder);
            Process command = new ProcessBuilder("cmd.exe", "/d", "/c", "mklink", "/J", folder.toString(),
                external.toString()).redirectErrorStream(true).start();
            if (!command.waitFor(15, TimeUnit.SECONDS))
            {
                command.destroyForcibly();
                throw new IOException("mklink timed out");
            }
            assertEquals("Windows junction fixture must be created", 0, command.exitValue());
            assertTrue("Fixture junction must point outside the project", Files.isSameFile(folder, external));
            assertSanitized(assertThrows(IOException.class, () -> project.child("module")), external);
        }
        finally
        {
            Files.deleteIfExists(folder);
        }
    }

    private static void assertSanitized(IOException failure, Path external)
    {
        assertFalse(failure.getMessage().contains(external.toString()));
        assertFalse(failure.getMessage().contains("outside sentinel"));
    }
}
