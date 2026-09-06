/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ozon.edt.mcp.server.tools.impl.vanessa;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.ozon.edt.mcp.server.tools.IMcpTool.ResponseType;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Tests for {@link VanessaOpenAllureReportTool}: static contract plus the pure
 * file/parameter logic. Never touches real generation or the HTTP server (no
 * subprocess, no ports) — those are exercised by e2e.
 */
public class VanessaOpenAllureReportToolTest
{
    private Path tmpDir;

    @Before
    public void setUp() throws Exception
    {
        tmpDir = Files.createTempDirectory("vanessa-allure-tool-test-"); //$NON-NLS-1$
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
    public void testName()
    {
        assertEquals("vanessa_open_allure_report", new VanessaOpenAllureReportTool().getName()); //$NON-NLS-1$
    }

    @Test
    public void testResponseTypeJson()
    {
        assertEquals(ResponseType.JSON, new VanessaOpenAllureReportTool().getResponseType());
    }

    @Test
    public void testExecuteWithoutSelectorFails()
    {
        assertFailure(new HashMap<>()); //$NON-NLS-1$
    }

    @Test
    public void testExecuteWithNonNumericLaunchIdFails()
    {
        Map<String, String> params = new HashMap<>();
        params.put("launchId", "abc"); //$NON-NLS-1$
        assertFailure(params);
    }

    @Test
    public void testExecuteWithMissingOutDirFails()
    {
        Map<String, String> params = new HashMap<>();
        params.put("outDir", "/no/such/dir"); //$NON-NLS-1$
        assertFailure(params);
    }

    @Test
    public void testExecuteWithOutDirWithoutAllureResultsFails() throws Exception
    {
        Path out = Files.createDirectories(tmpDir.resolve("out")); //$NON-NLS-1$
        Files.writeString(out.resolve("BDD.log"), "run", StandardCharsets.UTF_8); //$NON-NLS-1$

        Map<String, String> params = new HashMap<>();
        params.put("outDir", out.toString()); //$NON-NLS-1$
        assertFailure(params);
    }

    @Test
    public void testFindResultsDirPrefersAllureSubdir() throws Exception
    {
        Path out = Files.createDirectories(tmpDir.resolve("out")); //$NON-NLS-1$
        Files.createDirectories(out.resolve("allure")); //$NON-NLS-1$
        Files.writeString(out.resolve("allure/something-result.json"), "{}", StandardCharsets.UTF_8); //$NON-NLS-1$
        Files.writeString(out.resolve("root-result.json"), "{}", StandardCharsets.UTF_8); //$NON-NLS-1$

        Path found = AllureReportService.findResultsDir(out);
        assertNotNull(found);
        assertEquals(out.resolve("allure").toAbsolutePath(), found.toAbsolutePath());
    }

    @Test
    public void testFindResultsDirFallsBackToRoot() throws Exception
    {
        Path out = Files.createDirectories(tmpDir.resolve("out")); //$NON-NLS-1$
        Files.writeString(out.resolve("thing-result.json"), "{}", StandardCharsets.UTF_8); //$NON-NLS-1$

        Path found = AllureReportService.findResultsDir(out);
        assertNotNull(found);
        assertEquals(out.toAbsolutePath(), found.toAbsolutePath());
    }

    @Test
    public void testFindResultsDirReturnsNullForNoResults()
    {
        assertNull(AllureReportService.findResultsDir(tmpDir));
    }

    @Test
    public void testDeriveProjectFromOutPath()
    {
        assertEquals("afm", VanessaOpenAllureReportTool.deriveProject( //$NON-NLS-1$
            Path.of("/home/u/.1c-tools/vanessa/out/afm/allure"))); //$NON-NLS-1$
        assertEquals("afm", VanessaOpenAllureReportTool.deriveProject( //$NON-NLS-1$
            Path.of("/home/u/.1c-tools/vanessa/out/afm"))); //$NON-NLS-1$
        assertNull(VanessaOpenAllureReportTool.deriveProject(Path.of("/tmp/no-out-here"))); //$NON-NLS-1$
    }

    @Test
    public void testSchemaParamsLowerCamelCase()
    {
        String schema = new VanessaOpenAllureReportTool().getInputSchema();
        for (String key : new String[] { "launchId", "outDir", "detached", "allureBin", "generate" })
        {
            assertTrue("expected lowerCamelCase param " + key + " in schema", //$NON-NLS-1$ //$NON-NLS-2$
                schema.contains("\"" + key + "\"")); //$NON-NLS-1$
        }
    }

    @Test
    public void testErrorCarriesSuccessFalse()
    {
        assertFailure(new HashMap<>()); //$NON-NLS-1$
    }

    /** Executes with {@code params} and asserts the result is an error object. */
    private static void assertFailure(Map<String, String> params)
    {
        JsonObject o = JsonParser.parseString( //$NON-NLS-1$
            new VanessaOpenAllureReportTool().execute(params)).getAsJsonObject(); //$NON-NLS-1$
        assertEquals(false, o.get("success").getAsBoolean()); //$NON-NLS-1$
        assertTrue(o.has("error") && !o.get("error").getAsString().isEmpty()); //$NON-NLS-1$ //$NON-NLS-2$
    }
}
