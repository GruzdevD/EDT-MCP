/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl.vanessa;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;

import org.junit.Test;

import com.ditrix.edt.mcp.server.tools.IMcpTool.ResponseType;

/**
 * Tests for {@link VanessaListLaunchesTool}.
 * <p>
 * Enumerating launch configurations needs the live DebugPlugin, which is absent
 * headlessly; covered here is the static contract plus the name heuristic that
 * runs without a workbench.
 */
public class VanessaListLaunchesToolTest
{
    @Test
    public void testName()
    {
        assertEquals("vanessa_list_launches", new VanessaListLaunchesTool().getName()); //$NON-NLS-1$
    }

    @Test
    public void testNameConstant()
    {
        assertEquals(VanessaListLaunchesTool.NAME, new VanessaListLaunchesTool().getName());
    }

    @Test
    public void testResponseTypeJson()
    {
        assertEquals(ResponseType.JSON, new VanessaListLaunchesTool().getResponseType());
    }

    @Test
    public void testDescriptionNotEmpty()
    {
        String desc = new VanessaListLaunchesTool().getDescription();
        assertNotNull(desc);
        assertTrue(desc.length() > 0);
    }

    @Test
    public void testSchemaMentionsProjectFilter()
    {
        String schema = new VanessaListLaunchesTool().getInputSchema();
        assertNotNull(schema);
        assertTrue(schema.contains("object")); //$NON-NLS-1$
        assertTrue(schema.contains("projectFilter")); //$NON-NLS-1$
    }

    @Test
    public void testVanessaLikeHeuristic()
    {
        assertTrue(VanessaListLaunchesTool.isVanessaLike("vanessa Run")); //$NON-NLS-1$
        assertTrue(VanessaListLaunchesTool.isVanessaLike("BDD executor")); //$NON-NLS-1$
        assertTrue(VanessaListLaunchesTool.isVanessaLike("vrunner afm")); //$NON-NLS-1$
        assertFalse(VanessaListLaunchesTool.isVanessaLike("Тонкий клиент")); //$NON-NLS-1$
        assertFalse(VanessaListLaunchesTool.isVanessaLike(null)); //$NON-NLS-1$
    }
}
