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
 * Tests for {@link VanessaRunFeatureTool}.
 * <p>
 * The launch path needs a live EDT workbench and the out-of-repo project env.sh,
 * so the headless surface covered here is the static contract plus the
 * argument-validation error branches that return before any file/EDT access.
 */
public class VanessaRunFeatureToolTest
{
    @Test
    public void testName()
    {
        assertEquals("vanessa_run_feature", new VanessaRunFeatureTool().getName()); //$NON-NLS-1$
    }

    @Test
    public void testNameConstant()
    {
        assertEquals(VanessaRunFeatureTool.NAME, new VanessaRunFeatureTool().getName());
    }

    @Test
    public void testResponseTypeJson()
    {
        assertEquals(ResponseType.JSON, new VanessaRunFeatureTool().getResponseType());
    }

    @Test
    public void testDescriptionNotEmpty()
    {
        String desc = new VanessaRunFeatureTool().getDescription();
        assertNotNull(desc);
        assertTrue(desc.length() > 0);
    }

    @Test
    public void testSchemaIsValidObjectWithRequiredProject()
    {
        String schema = new VanessaRunFeatureTool().getInputSchema();
        assertNotNull(schema);
        assertTrue(schema.contains("object")); //$NON-NLS-1$
        assertTrue(schema.contains("project")); //$NON-NLS-1$
    }

    @Test
    public void testExecuteWithoutProjectFails()
    {
        String result = new VanessaRunFeatureTool().execute(new HashMap<>());
        assertNotNull(result);
        assertTrue(result.contains("\"success\": false")); //$NON-NLS-1$
    }

    @Test
    public void testExecuteWithUnknownProjectFails()
    {
        Map<String, String> params = new HashMap<>();
        // A project key with no out-of-repo env.sh must fail cleanly, before any
        // launch/file writes.
        params.put("project", "no_such_vanessa_project"); //$NON-NLS-1$
        String result = new VanessaRunFeatureTool().execute(params);
        assertNotNull(result);
        assertTrue(result.contains("\"success\": false")); //$NON-NLS-1$
    }
}
