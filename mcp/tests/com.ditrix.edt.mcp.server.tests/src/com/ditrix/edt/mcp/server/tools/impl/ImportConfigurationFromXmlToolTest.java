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
import java.util.UUID;

import org.junit.Test;

import com.ditrix.edt.mcp.server.tools.IMcpTool.ResponseType;

/**
 * Lightweight tests for {@link ImportConfigurationFromXmlTool} that exercise tool
 * metadata, JSON schema, and the argument/path-validation guards that fire BEFORE
 * the workspace project lookup — so they run headless.
 * <p>
 * {@code execute()} requires {@code importPath} + {@code projectName} up front, then
 * checks the source path on disk ("does not exist" / "is not a directory") using only
 * {@code java.nio.file}. The "project already exists" check and the real import need a
 * live workspace and are covered by the E2E suite.
 */
public class ImportConfigurationFromXmlToolTest
{
    @Test
    public void testName()
    {
        assertEquals("import_configuration_from_xml", new ImportConfigurationFromXmlTool().getName()); //$NON-NLS-1$
    }

    @Test
    public void testResponseType()
    {
        // import_configuration_from_xml is an action tool with no round-trip ID and
        // no machine-structured payload, so it returns MARKDOWN (see the
        // "Response format policy" in edt-mcp-tool-conventions).
        assertEquals(ResponseType.MARKDOWN, new ImportConfigurationFromXmlTool().getResponseType());
    }

    @Test
    public void testDescriptionNotEmpty()
    {
        String desc = new ImportConfigurationFromXmlTool().getDescription();
        assertNotNull(desc);
        assertFalse(desc.isEmpty());
    }

    @Test
    public void testGuideHoldsMigratedDetail()
    {
        // The exhaustive per-tool detail moved out of the always-loaded
        // description/schema into the on-demand getGuide() channel.
        String guide = new ImportConfigurationFromXmlTool().getGuide();
        assertNotNull(guide);
        assertFalse("getGuide() must be non-empty", guide.isEmpty()); //$NON-NLS-1$
        // A keyword that was migrated from the schema's parameter prose.
        assertTrue("guide must explain auto-detect behaviour", //$NON-NLS-1$
            guide.contains("auto-detect")); //$NON-NLS-1$
        assertTrue("guide must document the new-project constraint", //$NON-NLS-1$
            guide.contains("already exist")); //$NON-NLS-1$
    }

    @Test
    public void testInputSchemaContainsAllParameters()
    {
        String schema = new ImportConfigurationFromXmlTool().getInputSchema();
        assertNotNull(schema);
        assertTrue(schema.contains("\"importPath\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"projectName\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"projectNature\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"xmlVersion\"")); //$NON-NLS-1$
    }

    @Test
    public void testRequiredArrayMarksOnlyMandatoryParams()
    {
        String schema = new ImportConfigurationFromXmlTool().getInputSchema();
        int requiredIdx = schema.indexOf("\"required\""); //$NON-NLS-1$
        assertTrue("schema must declare required array", requiredIdx >= 0); //$NON-NLS-1$
        String tail = schema.substring(requiredIdx);
        assertTrue("importPath must be required", tail.contains("\"importPath\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("projectName must be required", tail.contains("\"projectName\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("projectNature must NOT be required", //$NON-NLS-1$
            tail.contains("\"projectNature\",") || tail.contains(",\"projectNature\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("xmlVersion must NOT be required", //$NON-NLS-1$
            tail.contains("\"xmlVersion\",") || tail.contains(",\"xmlVersion\"")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    // ==================== Argument & path validation (returns before the workspace lookup) ===========

    @Test
    public void testExecuteMissingImportPathIsError()
    {
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "NewConfig"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new ImportConfigurationFromXmlTool().execute(params);
        assertTrue("missing importPath must produce 'importPath is required'", //$NON-NLS-1$
            result.contains("importPath is required")); //$NON-NLS-1$
    }

    @Test
    public void testExecuteMissingProjectNameIsError()
    {
        Map<String, String> params = new HashMap<>();
        params.put("importPath", "C:/tmp/xml-src"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new ImportConfigurationFromXmlTool().execute(params);
        assertTrue("missing projectName must produce 'projectName is required'", //$NON-NLS-1$
            result.contains("projectName is required")); //$NON-NLS-1$
    }

    @Test
    public void testExecuteNonExistentImportPathIsError()
    {
        // A source path that does not exist is rejected on disk, before the
        // workspace project lookup — reachable headless.
        String nonExistent = System.getProperty("java.io.tmpdir") //$NON-NLS-1$
            + "/import-does-not-exist-" + UUID.randomUUID(); //$NON-NLS-1$
        Map<String, String> params = new HashMap<>();
        params.put("importPath", nonExistent); //$NON-NLS-1$
        params.put("projectName", "NewConfig"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new ImportConfigurationFromXmlTool().execute(params);
        assertTrue("a non-existent importPath must be rejected", //$NON-NLS-1$
            result.contains("does not exist")); //$NON-NLS-1$
        assertTrue("error payload must be JSON", result.trim().startsWith("{")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testExecuteImportPathIsAFileIsError() throws IOException
    {
        // A source path that is a file (not a directory) is rejected on disk,
        // before the workspace project lookup.
        Path tempFile = Files.createTempFile("import-cfg-not-a-dir", ".tmp"); //$NON-NLS-1$ //$NON-NLS-2$
        try
        {
            Map<String, String> params = new HashMap<>();
            params.put("importPath", tempFile.toString()); //$NON-NLS-1$
            params.put("projectName", "NewConfig"); //$NON-NLS-1$ //$NON-NLS-2$
            String result = new ImportConfigurationFromXmlTool().execute(params);
            assertTrue("a file importPath must be rejected as not a directory", //$NON-NLS-1$
                result.contains("is not a directory")); //$NON-NLS-1$
        }
        finally
        {
            Files.deleteIfExists(tempFile);
        }
    }
}
