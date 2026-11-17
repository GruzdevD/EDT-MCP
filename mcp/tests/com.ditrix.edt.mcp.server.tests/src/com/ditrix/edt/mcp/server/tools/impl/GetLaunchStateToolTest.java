/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.ditrix.edt.mcp.server.tools.IMcpTool.ResponseType;

/**
 * Tests for {@link GetLaunchStateTool}.
 * <p>
 * The live surface (launch list, OS process sweep, modal window probe) needs a
 * running Eclipse debug platform and is covered by the E2E suite. The headless
 * surface is the static contract: name constant, JSON response type, a
 * description that advertises the hang-diagnosis use case, a schema with the
 * three documented parameters, and a non-empty bundled guide.
 */
public class GetLaunchStateToolTest
{
    @Test
    public void testName()
    {
        assertEquals("get_launch_state", new GetLaunchStateTool().getName()); //$NON-NLS-1$
    }

    @Test
    public void testNameConstant()
    {
        assertEquals(GetLaunchStateTool.NAME, new GetLaunchStateTool().getName());
    }

    @Test
    public void testResponseTypeJson()
    {
        assertEquals(ResponseType.JSON, new GetLaunchStateTool().getResponseType());
    }

    @Test
    public void testDescriptionNotEmpty()
    {
        String desc = new GetLaunchStateTool().getDescription();
        assertNotNull(desc);
        assertTrue(desc.length() > 0);
    }

    @Test
    public void testDescriptionMentionsLaunchesAndModalWindows()
    {
        String desc = new GetLaunchStateTool().getDescription().toLowerCase();
        assertTrue(desc.contains("launch")); //$NON-NLS-1$
        assertTrue(desc.contains("modal")); //$NON-NLS-1$
    }

    @Test
    public void testSchemaIsValidObject()
    {
        String schema = new GetLaunchStateTool().getInputSchema();
        assertNotNull(schema);
        assertTrue(schema.contains("object")); //$NON-NLS-1$
    }

    @Test
    public void testSchemaDocumentsAllThreeParameters()
    {
        String schema = new GetLaunchStateTool().getInputSchema();
        assertTrue(schema.contains("projectName")); //$NON-NLS-1$
        assertTrue(schema.contains("includeTerminated")); //$NON-NLS-1$
        assertTrue(schema.contains("tailLines")); //$NON-NLS-1$
    }

    @Test
    public void testGuideNonEmpty()
    {
        String guide = new GetLaunchStateTool().getGuide();
        assertNotNull(guide);
        assertTrue(guide.length() > 0);
    }
}
