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
 * Tests for {@link PluginUpdateTool}.
 * <p>
 * The real deploy writes into {@code ~/.p2/pool/plugins/} and the running EDT's
 * {@code bundles.info}; those need a live environment and are covered live. Here
 * we pin the static contract and the deploy mechanics tested in
 * {@link PluginSelfUpdateTest}.
 */
public class PluginUpdateToolTest
{
    @Test
    public void testName()
    {
        assertEquals("plugin_update", new PluginUpdateTool().getName()); //$NON-NLS-1$
    }

    @Test
    public void testNameConstant()
    {
        assertEquals(PluginUpdateTool.NAME, new PluginUpdateTool().getName());
    }

    @Test
    public void testResponseTypeJson()
    {
        assertEquals(ResponseType.JSON, new PluginUpdateTool().getResponseType());
    }

    @Test
    public void testDescriptionNotEmptyAndNamesTheSiblingTool()
    {
        String desc = new PluginUpdateTool().getDescription();
        assertNotNull(desc);
        assertTrue(desc.length() > 0);
        assertTrue(desc.contains("plugin_check_for_update")); //$NON-NLS-1$
    }

    @Test
    public void testSchemaDeclaresOverrideParams()
    {
        String schema = new PluginUpdateTool().getInputSchema();
        assertNotNull(schema);
        assertTrue(schema.contains(PluginSelfUpdate.KEY_REPO)); //$NON-NLS-1$
        assertTrue(schema.contains(PluginSelfUpdate.KEY_BRANCH)); //$NON-NLS-1$
    }
}
