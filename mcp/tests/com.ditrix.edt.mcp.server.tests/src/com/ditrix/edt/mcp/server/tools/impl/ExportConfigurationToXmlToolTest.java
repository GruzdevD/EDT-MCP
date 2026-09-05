/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

import com.ditrix.edt.mcp.server.tools.IMcpTool.ResponseType;

/**
 * Lightweight tests for {@link ExportConfigurationToXmlTool} that exercise tool
 * metadata, JSON schema, and the argument/path-validation guards that fire BEFORE
 * any EDT CLI access — so they run headless.
 * <p>
 * {@code execute()} requires {@code projectName} + {@code outputPath} up front, then
 * does a filesystem check ("outputPath exists but is not a directory") that touches
 * only {@code java.nio.file}, not EDT. The real export needs a live workspace and is
 * covered by the E2E suite.
 */
public class ExportConfigurationToXmlToolTest
{
    @Test
    public void testName()
    {
        assertEquals("export_configuration_to_xml", new ExportConfigurationToXmlTool().getName()); //$NON-NLS-1$
    }

    @Test
    public void testResponseType()
    {
        // export_configuration_to_xml is an action tool with no round-trip ID and
        // no machine-structured payload, so it returns MARKDOWN (see the
        // "Response format policy" in edt-mcp-tool-conventions).
        assertEquals(ResponseType.MARKDOWN, new ExportConfigurationToXmlTool().getResponseType());
    }

    @Test
    public void testDescriptionNotEmpty()
    {
        String desc = new ExportConfigurationToXmlTool().getDescription();
        assertNotNull(desc);
        assertFalse(desc.isEmpty());
    }

    @Test
    public void testInputSchemaContainsBothParameters()
    {
        String schema = new ExportConfigurationToXmlTool().getInputSchema();
        assertNotNull(schema);
        assertTrue(schema.contains("\"projectName\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"outputPath\"")); //$NON-NLS-1$
    }

    @Test
    public void testBothParamsRequired()
    {
        String schema = new ExportConfigurationToXmlTool().getInputSchema();
        int requiredIdx = schema.indexOf("\"required\""); //$NON-NLS-1$
        assertTrue("schema must declare required array", requiredIdx >= 0); //$NON-NLS-1$
        String tail = schema.substring(requiredIdx);
        assertTrue("projectName must be required", tail.contains("\"projectName\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("outputPath must be required", tail.contains("\"outputPath\"")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testGuideNotEmpty()
    {
        String guide = new ExportConfigurationToXmlTool().getGuide();
        assertNotNull(guide);
        assertFalse(guide.isEmpty());
    }

    // ==================== Argument & path validation (returns before any EDT access) ===========

    @Test
    public void testExecuteMissingProjectNameIsError()
    {
        Map<String, String> params = new HashMap<>();
        params.put("outputPath", "C:/tmp/out"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new ExportConfigurationToXmlTool().execute(params);
        assertTrue("missing projectName must produce 'projectName is required'", //$NON-NLS-1$
            result.contains("projectName is required")); //$NON-NLS-1$
    }

    @Test
    public void testExecuteMissingOutputPathIsError()
    {
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "MyConfig"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new ExportConfigurationToXmlTool().execute(params);
        assertTrue("missing outputPath must produce 'outputPath is required'", //$NON-NLS-1$
            result.contains("outputPath is required")); //$NON-NLS-1$
    }

    @Test
    public void testExecuteOutputPathIsAFileIsError() throws IOException
    {
        // The "exists but is not a directory" guard runs purely on java.nio.file,
        // before any EDT CLI lookup — so it is reachable headless.
        Path tempFile = Files.createTempFile("export-cfg-not-a-dir", ".tmp"); //$NON-NLS-1$ //$NON-NLS-2$
        try
        {
            Map<String, String> params = new HashMap<>();
            params.put("projectName", "MyConfig"); //$NON-NLS-1$ //$NON-NLS-2$
            params.put("outputPath", tempFile.toString()); //$NON-NLS-1$
            String result = new ExportConfigurationToXmlTool().execute(params);
            assertTrue("a file outputPath must be rejected as not a directory", //$NON-NLS-1$
                result.contains("is not a directory")); //$NON-NLS-1$
            assertTrue("error payload must be JSON", result.trim().startsWith("{")); //$NON-NLS-1$ //$NON-NLS-2$
        }
        finally
        {
            Files.deleteIfExists(tempFile);
        }
    }
}
