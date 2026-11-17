/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

import com.ditrix.edt.mcp.server.tools.IMcpTool.ResponseType;

/**
 * Tests for {@link ImportProjectTool}.
 * <p>
 * Covers tool metadata, the input/output schema (including the optional {@code projectName}
 * rename), the guide, and the pure-filesystem argument guards (missing path, missing
 * directory, missing .project), which return BEFORE any workspace access. The actual
 * linked-project import needs a live workspace, so it is covered by the E2E
 * round-trip (copy fixture -> import -> verify -> delete).
 */
public class ImportProjectToolTest
{
    @Test
    public void testName()
    {
        assertEquals("import_project", new ImportProjectTool().getName()); //$NON-NLS-1$
    }

    @Test
    public void testNameConstant()
    {
        assertEquals(ImportProjectTool.NAME, new ImportProjectTool().getName());
    }

    @Test
    public void testResponseTypeJson()
    {
        assertEquals(ResponseType.JSON, new ImportProjectTool().getResponseType());
    }

    @Test
    public void testDescriptionMentionsExistingAndSteersToGuide()
    {
        String desc = new ImportProjectTool().getDescription();
        assertNotNull(desc);
        assertTrue(desc.length() > 0);
        assertTrue("description must advertise the EXISTING-project scope", //$NON-NLS-1$
            desc.toUpperCase().contains("EXISTING")); //$NON-NLS-1$
        assertTrue("description must steer to the on-demand guide", //$NON-NLS-1$
            desc.contains("get_tool_guide('import_project')")); //$NON-NLS-1$
    }

    @Test
    public void testInputSchemaDeclaresParameters()
    {
        String schema = new ImportProjectTool().getInputSchema();
        assertNotNull(schema);
        assertTrue(schema.contains("\"path\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"projectName\"")); //$NON-NLS-1$
    }

    @Test
    public void testRequiredParameters()
    {
        String schema = new ImportProjectTool().getInputSchema();
        int requiredIdx = schema.indexOf("\"required\""); //$NON-NLS-1$
        assertTrue("schema must declare a required array", requiredIdx >= 0); //$NON-NLS-1$
        String tail = schema.substring(requiredIdx);
        assertTrue("path must be required", tail.contains("\"path\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("projectName must NOT be required", !tail.contains("\"projectName\"")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testOutputSchemaDeclaresResultFields()
    {
        String schema = new ImportProjectTool().getOutputSchema();
        assertNotNull(schema);
        assertTrue("outputSchema must declare action", schema.contains("\"action\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("outputSchema must declare state", schema.contains("\"state\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("outputSchema must declare renamed", schema.contains("\"renamed\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("outputSchema must declare location", schema.contains("\"location\"")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testGuideDocumentsRenameAndSlots()
    {
        String guide = new ImportProjectTool().getGuide();
        assertNotNull(guide);
        assertTrue(guide.length() > 0);
        assertTrue("guide must document the projectName rename", guide.contains("projectName")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("guide must document the parallel-slot use case", //$NON-NLS-1$
            guide.toLowerCase().contains("slot")); //$NON-NLS-1$
        assertTrue("guide must document the name-collision gotcha", //$NON-NLS-1$
            guide.toLowerCase().contains("conflict")); //$NON-NLS-1$
    }

    // ==================== Argument validation (returns before any workspace access) ====================

    @Test
    public void testMissingPathIsError()
    {
        Map<String, String> params = new HashMap<>();
        String result = new ImportProjectTool().execute(params);
        assertTrue(result.contains("path is required")); //$NON-NLS-1$
    }

    @Test
    public void testMissingDirectoryIsError()
    {
        Map<String, String> params = new HashMap<>();
        params.put("path", "/no/such/dir/import_project_ut_zzz"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new ImportProjectTool().execute(params);
        assertTrue(result.contains("No such directory")); //$NON-NLS-1$
    }
}
