/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl.vanessa;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

import com.ditrix.edt.mcp.server.tools.IMcpTool.ResponseType;

/**
 * Tests for {@link VanessaGetExecutionStatusTool}.
 * <p>
 * Status resolution reads VA's on-disk BDDStatus.log artifact for a tracked run,
 * which needs a live EDT session; the headless surface covered here is the static
 * contract plus the argument-validation error branches.
 */
public class VanessaGetExecutionStatusToolTest
{
    @Test
    public void testName()
    {
        assertEquals("vanessa_get_execution_status", new VanessaGetExecutionStatusTool().getName()); //$NON-NLS-1$
    }

    @Test
    public void testNameConstant()
    {
        assertEquals(VanessaGetExecutionStatusTool.NAME, new VanessaGetExecutionStatusTool().getName());
    }

    @Test
    public void testResponseTypeJson()
    {
        assertEquals(ResponseType.JSON, new VanessaGetExecutionStatusTool().getResponseType());
    }

    @Test
    public void testDescriptionNotEmpty()
    {
        String desc = new VanessaGetExecutionStatusTool().getDescription();
        assertNotNull(desc);
        assertTrue(desc.length() > 0);
    }

    @Test
    public void testSchemaMentionsLaunchId()
    {
        String schema = new VanessaGetExecutionStatusTool().getInputSchema();
        assertNotNull(schema);
        assertTrue(schema.contains("object")); //$NON-NLS-1$
        assertTrue(schema.contains("launchId")); //$NON-NLS-1$
    }

    @Test
    public void testExecuteWithoutLaunchIdFails()
    {
        String result = new VanessaGetExecutionStatusTool().execute(new HashMap<>());
        assertNotNull(result);
        assertTrue(result.contains("\"success\": false")); //$NON-NLS-1$
    }

    @Test
    public void testExecuteWithNonNumericLaunchIdFails()
    {
        Map<String, String> params = new HashMap<>();
        params.put("launchId", "not-a-number"); //$NON-NLS-1$
        String result = new VanessaGetExecutionStatusTool().execute(params);
        assertNotNull(result);
        assertTrue(result.contains("\"success\": false")); //$NON-NLS-1$
    }

    @Test
    public void testExecuteWithUnknownLaunchIdFails()
    {
        Map<String, String> params = new HashMap<>();
        params.put("launchId", "999999"); //$NON-NLS-1$
        String result = new VanessaGetExecutionStatusTool().execute(params);
        assertNotNull(result);
        assertTrue(result.contains("\"success\": false")); //$NON-NLS-1$
    }
}
