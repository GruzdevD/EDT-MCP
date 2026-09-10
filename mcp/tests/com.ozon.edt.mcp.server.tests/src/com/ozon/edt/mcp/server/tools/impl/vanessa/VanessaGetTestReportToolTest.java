/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ozon.edt.mcp.server.tools.impl.vanessa;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

import com.ozon.edt.mcp.server.tools.IMcpTool.ResponseType;

/**
 * Tests for {@link VanessaGetTestReportTool}.
 * <p>
 * Parsing a junit.xml report reads the file system, so the headless surface
 * covered here is the static contract plus the argument-validation error
 * branches that return before any file access.
 */
public class VanessaGetTestReportToolTest
{
    @Test
    public void testName()
    {
        assertEquals("vanessa_get_test_report", new VanessaGetTestReportTool().getName()); //$NON-NLS-1$
    }

    @Test
    public void testNameConstant()
    {
        assertEquals(VanessaGetTestReportTool.NAME, new VanessaGetTestReportTool().getName());
    }

    @Test
    public void testResponseTypeJson()
    {
        assertEquals(ResponseType.JSON, new VanessaGetTestReportTool().getResponseType());
    }

    @Test
    public void testDescriptionNotEmpty()
    {
        String desc = new VanessaGetTestReportTool().getDescription();
        assertNotNull(desc);
        assertTrue(desc.length() > 0);
    }

    @Test
    public void testSchemaMentionsBothSelectors()
    {
        String schema = new VanessaGetTestReportTool().getInputSchema();
        assertNotNull(schema);
        assertTrue(schema.contains("object")); //$NON-NLS-1$
        assertTrue(schema.contains("junitReportPath")); //$NON-NLS-1$
        assertTrue(schema.contains("launchId")); //$NON-NLS-1$
    }

    @Test
    public void testExecuteWithoutSelectorsFails()
    {
        String result = new VanessaGetTestReportTool().execute(new HashMap<>());
        assertNotNull(result);
        assertTrue(result.contains("\"success\":false")); //$NON-NLS-1$
    }

    @Test
    public void testExecuteWithUnknownLaunchIdFails()
    {
        Map<String, String> params = new HashMap<>();
        params.put("launchId", "999999"); //$NON-NLS-1$
        String result = new VanessaGetTestReportTool().execute(params);
        assertNotNull(result);
        assertTrue(result.contains("\"success\":false")); //$NON-NLS-1$
    }

    @Test
    public void testExecuteWithMissingFileFails()
    {
        Map<String, String> params = new HashMap<>();
        params.put("junitReportPath", "/no/such/path/junit.xml"); //$NON-NLS-1$
        String result = new VanessaGetTestReportTool().execute(params);
        assertNotNull(result);
        assertTrue(result.contains("\"success\":false")); //$NON-NLS-1$
    }
}
