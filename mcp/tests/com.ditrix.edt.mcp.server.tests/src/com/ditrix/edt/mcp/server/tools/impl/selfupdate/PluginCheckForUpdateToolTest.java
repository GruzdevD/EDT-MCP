/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl.selfupdate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.ditrix.edt.mcp.server.tools.IMcpTool.ResponseType;

/**
 * Tests for {@link PluginCheckForUpdateTool}.
 * <p>
 * The real fetch runs a git subprocess against the user's repo and the running
 * bundle, which are absent headlessly; covered here is the static contract plus
 * the tool's parameter defaults.
 */
public class PluginCheckForUpdateToolTest
{
    @Test
    public void testName()
    {
        assertEquals("plugin_check_for_update", new PluginCheckForUpdateTool().getName()); //$NON-NLS-1$
    }

    @Test
    public void testNameConstant()
    {
        assertEquals(PluginCheckForUpdateTool.NAME, new PluginCheckForUpdateTool().getName());
    }

    @Test
    public void testResponseTypeJson()
    {
        assertEquals(ResponseType.JSON, new PluginCheckForUpdateTool().getResponseType());
    }

    @Test
    public void testDescriptionNotEmpty()
    {
        String desc = new PluginCheckForUpdateTool().getDescription();
        assertNotNull(desc);
        assertTrue(desc.length() > 0);
    }

    @Test
    public void testSchemaDeclaresOverrideParams()
    {
        String schema = new PluginCheckForUpdateTool().getInputSchema();
        assertNotNull(schema);
        assertTrue(schema.contains(PluginSelfUpdate.KEY_REPO)); //$NON-NLS-1$
        assertTrue(schema.contains(PluginSelfUpdate.KEY_BRANCH)); //$NON-NLS-1$
        assertTrue(schema.contains(PluginSelfUpdate.DEFAULT_BRANCH)); //$NON-NLS-1$
    }

    @Test
    public void testDefaultsMatchTheProjectRepo()
    {
        assertEquals("update-site", PluginSelfUpdate.DEFAULT_BRANCH); //$NON-NLS-1$
        assertTrue(PluginSelfUpdate.DEFAULT_REPO.contains("gitlab.ozon.ru")); //$NON-NLS-1$
    }
}
