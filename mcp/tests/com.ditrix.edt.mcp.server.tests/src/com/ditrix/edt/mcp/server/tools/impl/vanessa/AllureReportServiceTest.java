/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl.vanessa;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Headless tests for {@link AllureReportService} file operations: result/report dir
 * detection, the cleanup helpers and project derivation. The real {@code allure}
 * subprocess generation is out of scope here (needs the CLI + a subprocess) — it is
 * exercised by e2e, matching the tool test's philosophy.
 */
public class AllureReportServiceTest
{
    private Path tmpDir;

    @Before
    public void setUp() throws Exception
    {
        tmpDir = Files.createTempDirectory("allure-report-service-test-"); //$NON-NLS-1$
    }

    @After
    public void tearDown() throws Exception
    {
        if (tmpDir != null && Files.exists(tmpDir))
        {
            try (java.util.stream.Stream<Path> s = Files.walk(tmpDir))
            {
                s.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
    }

    @Test
    public void testIsResultsDirTrueWithResultJson() throws Exception
    {
        Path dir = Files.createDirectories(tmpDir.resolve("res")); //$NON-NLS-1$
        Files.writeString(dir.resolve("a-result.json"), "{}", StandardCharsets.UTF_8); //$NON-NLS-1$
        assertTrue(AllureReportService.isResultsDir(dir));
    }

    @Test
    public void testIsResultsDirFalseWithoutResultJson() throws Exception
    {
        Path dir = Files.createDirectories(tmpDir.resolve("res")); //$NON-NLS-1$
        Files.writeString(dir.resolve("notes.txt"), "x", StandardCharsets.UTF_8); //$NON-NLS-1$
        assertFalse(AllureReportService.isResultsDir(dir));
    }

    @Test
    public void testIsResultsDirFalseForMissingDir()
    {
        assertFalse(AllureReportService.isResultsDir(tmpDir.resolve("nope"))); //$NON-NLS-1$
        assertFalse(AllureReportService.isResultsDir(null));
    }

    @Test
    public void testFindResultsDirPrefersAllureSubdir() throws Exception
    {
        Path out = Files.createDirectories(tmpDir.resolve("out")); //$NON-NLS-1$
        Files.createDirectories(out.resolve("allure")); //$NON-NLS-1$
        Files.writeString(out.resolve("allure/x-result.json"), "{}", StandardCharsets.UTF_8); //$NON-NLS-1$
        Path found = AllureReportService.findResultsDir(out);
        assertTrue(found != null && found.toAbsolutePath().equals(out.resolve("allure").toAbsolutePath())); //$NON-NLS-1$
    }

    @Test
    public void testClearReportRemovesWholeTree() throws Exception
    {
        Path report = Files.createDirectories(tmpDir.resolve("allure-report")); //$NON-NLS-1$
        Files.createDirectories(report.resolve("history")); //$NON-NLS-1$
        Files.createDirectories(report.resolve("app").resolve("js")); //$NON-NLS-1$
        Files.writeString(report.resolve("index.html"), "<html></html>", StandardCharsets.UTF_8); //$NON-NLS-1$
        Files.writeString(report.resolve("app/js/app.js"), "x", StandardCharsets.UTF_8); //$NON-NLS-1$

        AllureReportService.clearReport(report);
        assertFalse(Files.exists(report));
    }

    @Test
    public void testClearReportMissingDirIsNoop() throws Exception
    {
        AllureReportService.clearReport(tmpDir.resolve("missing")); //$NON-NLS-1$
        assertTrue(Files.exists(tmpDir)); //$NON-NLS-1$
    }

    @Test
    public void testClearResultsRemovesOnlyAllureArtifacts() throws Exception
    {
        Path dir = Files.createDirectories(tmpDir.resolve("res")); //$NON-NLS-1$
        Files.writeString(dir.resolve("a-result.json"), "{}", StandardCharsets.UTF_8); //$NON-NLS-1$
        Files.writeString(dir.resolve("b-container.json"), "{}", StandardCharsets.UTF_8); //$NON-NLS-1$
        Files.writeString(dir.resolve("c-attachment.png"), "png", StandardCharsets.UTF_8); //$NON-NLS-1$
        Files.writeString(dir.resolve("executors.json"), "{}", StandardCharsets.UTF_8); //$NON-NLS-1$
        Files.writeString(dir.resolve("categories.json"), "{}", StandardCharsets.UTF_8); //$NON-NLS-1$
        Files.writeString(dir.resolve("environment.properties"), "k=v", StandardCharsets.UTF_8); //$NON-NLS-1$
        Files.createDirectories(dir.resolve("history")); //$NON-NLS-1$
        Files.writeString(dir.resolve("history/old.json"), "{}", StandardCharsets.UTF_8); //$NON-NLS-1$
        Files.writeString(dir.resolve("keep.log"), "run", StandardCharsets.UTF_8); //$NON-NLS-1$
        Files.writeString(dir.resolve("notes.txt"), "mine", StandardCharsets.UTF_8); //$NON-NLS-1$

        int removed = AllureReportService.clearResults(dir);
        assertEquals(7, removed);
        assertFalse(Files.exists(dir.resolve("a-result.json"))); //$NON-NLS-1$
        assertFalse(Files.exists(dir.resolve("b-container.json"))); //$NON-NLS-1$
        assertFalse(Files.exists(dir.resolve("c-attachment.png"))); //$NON-NLS-1$
        assertFalse(Files.exists(dir.resolve("executors.json"))); //$NON-NLS-1$
        assertFalse(Files.exists(dir.resolve("categories.json"))); //$NON-NLS-1$
        assertFalse(Files.exists(dir.resolve("environment.properties"))); //$NON-NLS-1$
        assertFalse(Files.exists(dir.resolve("history"))); //$NON-NLS-1$
        assertTrue(Files.exists(dir.resolve("keep.log"))); //$NON-NLS-1$
        assertTrue(Files.exists(dir.resolve("notes.txt"))); //$NON-NLS-1$
    }

    @Test
    public void testClearResultsMissingOrNullDirReturnsZero() throws Exception
    {
        assertEquals(0, AllureReportService.clearResults(tmpDir.resolve("missing"))); //$NON-NLS-1$
        assertEquals(0, AllureReportService.clearResults(null));
    }

    @Test
    public void testDeriveProjectFromOutPath()
    {
        assertEquals("afm", AllureReportService.deriveProject( //$NON-NLS-1$
            Path.of("/home/u/.1c-tools/vanessa/out/afm/allure"))); //$NON-NLS-1$
        assertEquals("afm", AllureReportService.deriveProject( //$NON-NLS-1$
            Path.of("/home/u/.1c-tools/vanessa/out/afm"))); //$NON-NLS-1$
        assertNull(AllureReportService.deriveProject(Path.of("/tmp/no-out-here"))); //$NON-NLS-1$
    }

    @Test
    public void testTailSlicesOutput()
    {
        String out = String.join("\n", "l1", "l2", "l3", "l4", "l5", "l6", "l7", "l8"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$ //$NON-NLS-8$
        assertEquals("l3 | l4 | l5 | l6 | l7 | l8", AllureReportService.tail(out)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        assertEquals("(no output)", AllureReportService.tail("")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("(no output)", AllureReportService.tail(null)); //$NON-NLS-1$
    }
}
