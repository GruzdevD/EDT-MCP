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
 * Tests for {@link CreateSpecterLaunchConfigTool}.
 *
 * <p>Covers tool metadata, input/output schema declarations, the required-argument
 * guard (baseLaunchConfig) and the guide. The actual
 * {@code ILaunchConfigurationType.newInstance} + {@code doSave} path requires a live
 * {@link org.eclipse.debug.core.DebugPlugin} and is therefore e2e-only
 * (see {@code tests/e2e/tools/test_create_specter_launch_config.py}).
 */
public class CreateSpecterLaunchConfigToolTest
{
    @Test
    public void testName()
    {
        assertEquals("create_specter_launch_config", new CreateSpecterLaunchConfigTool().getName()); //$NON-NLS-1$
    }

    @Test
    public void testNameConstant()
    {
        assertEquals(CreateSpecterLaunchConfigTool.NAME, new CreateSpecterLaunchConfigTool().getName());
    }

    @Test
    public void testResponseTypeIsJson()
    {
        assertEquals(ResponseType.JSON, new CreateSpecterLaunchConfigTool().getResponseType());
    }

    @Test
    public void testDescriptionSteersToGuide()
    {
        String desc = new CreateSpecterLaunchConfigTool().getDescription();
        assertNotNull(desc);
        assertTrue(desc.length() > 0);
        assertTrue("description must steer to the on-demand guide", //$NON-NLS-1$
            desc.contains("get_tool_guide('create_specter_launch_config')")); //$NON-NLS-1$
    }

    @Test
    public void testInputSchemaDeclaresAllParameters()
    {
        String schema = new CreateSpecterLaunchConfigTool().getInputSchema();
        assertNotNull(schema);
        assertTrue("schema must declare baseLaunchConfig", schema.contains("\"baseLaunchConfig\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("schema must declare name", schema.contains("\"name\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("schema must declare testingPort", schema.contains("\"testingPort\"")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testInputSchemaRequiredContainsBaseLaunchConfigOnly()
    {
        String schema = new CreateSpecterLaunchConfigTool().getInputSchema();
        int requiredIdx = schema.indexOf("\"required\""); //$NON-NLS-1$
        assertTrue("schema must declare a required array", requiredIdx >= 0); //$NON-NLS-1$
        String tail = schema.substring(requiredIdx);
        assertTrue("baseLaunchConfig must be required", tail.contains("\"baseLaunchConfig\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("name must NOT be required", !tail.contains("\"name\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("testingPort must NOT be required", !tail.contains("\"testingPort\"")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testOutputSchemaDeclaresResultFields()
    {
        String schema = new CreateSpecterLaunchConfigTool().getOutputSchema();
        assertNotNull(schema);
        assertTrue("outputSchema must declare success", schema.contains("\"success\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("outputSchema must declare action", schema.contains("\"action\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("outputSchema must declare name", schema.contains("\"name\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("outputSchema must declare baseLaunchConfig", schema.contains("\"baseLaunchConfig\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("outputSchema must declare testingPort", schema.contains("\"testingPort\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("outputSchema must declare type", schema.contains("\"type\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("outputSchema must declare message", schema.contains("\"message\"")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testGuideIsNonEmpty()
    {
        String guide = new CreateSpecterLaunchConfigTool().getGuide();
        assertNotNull(guide);
        assertTrue("guide must be non-empty", guide.length() > 0); //$NON-NLS-1$
        assertTrue("guide must mention the base runtime-client config", //$NON-NLS-1$
            guide.contains("baseLaunchConfig")); //$NON-NLS-1$
    }

    @Test
    public void testExecuteRequiresBaseLaunchConfig()
    {
        Map<String, String> params = new HashMap<>();
        String result = new CreateSpecterLaunchConfigTool().execute(params);
        assertNotNull(result);
        assertTrue("missing baseLaunchConfig must produce an error JSON", //$NON-NLS-1$
            result.contains("\"error\"") || result.contains("\"isError\"")); //$NON-NLS-1$ //$NON-NLS-2$
    }
}
