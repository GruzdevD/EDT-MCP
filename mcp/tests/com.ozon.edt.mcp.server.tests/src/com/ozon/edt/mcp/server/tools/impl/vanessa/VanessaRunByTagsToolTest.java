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
 * Tests for {@link VanessaRunByTagsTool}.
 * <p>
 * The launch path needs a live EDT workbench and the out-of-repo project env.sh,
 * so the headless surface covered here is the static contract plus the
 * argument-validation error branches that return before any file/EDT access.
 */
public class VanessaRunByTagsToolTest
{
    @Test
    public void testName()
    {
        assertEquals("vanessa_run_by_tags", new VanessaRunByTagsTool().getName()); //$NON-NLS-1$
    }

    @Test
    public void testNameConstant()
    {
        assertEquals(VanessaRunByTagsTool.NAME, new VanessaRunByTagsTool().getName());
    }

    @Test
    public void testResponseTypeJson()
    {
        assertEquals(ResponseType.JSON, new VanessaRunByTagsTool().getResponseType());
    }

    @Test
    public void testDescriptionNotEmpty()
    {
        String desc = new VanessaRunByTagsTool().getDescription();
        assertNotNull(desc);
        assertTrue(desc.length() > 0);
    }

    @Test
    public void testSchemaRequiresProjectAndTags()
    {
        String schema = new VanessaRunByTagsTool().getInputSchema();
        assertNotNull(schema);
        assertTrue(schema.contains("project")); //$NON-NLS-1$
        assertTrue(schema.contains("tags")); //$NON-NLS-1$
    }

    @Test
    public void testExecuteWithoutProjectFails()
    {
        Map<String, String> params = new HashMap<>();
        params.put("tags", "smoke"); //$NON-NLS-1$
        assertTrue(new VanessaRunByTagsTool().execute(params)
            .contains("\"success\": false")); //$NON-NLS-1$
    }

    @Test
    public void testExecuteWithoutTagsFails()
    {
        Map<String, String> params = new HashMap<>();
        params.put("project", "afm"); //$NON-NLS-1$
        assertTrue(new VanessaRunByTagsTool().execute(params)
            .contains("\"success\": false")); //$NON-NLS-1$
    }

    @Test
    public void testExecuteWithUnknownProjectFails()
    {
        Map<String, String> params = new HashMap<>();
        params.put("project", "no_such_vanessa_project"); //$NON-NLS-1$
        params.put("tags", "smoke"); //$NON-NLS-1$
        assertTrue(new VanessaRunByTagsTool().execute(params)
            .contains("\"success\": false")); //$NON-NLS-1$
    }
}
