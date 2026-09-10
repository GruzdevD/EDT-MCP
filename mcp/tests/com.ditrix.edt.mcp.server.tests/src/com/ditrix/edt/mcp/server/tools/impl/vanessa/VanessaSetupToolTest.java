/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl.vanessa;

import static org.junit.Assert.assertTrue;

import java.util.Collections;

import org.junit.Test;

/**
 * Tests the {@link VanessaSetupTool} contract. The provisioning/download path runs as a
 * background job in live EDT (it needs a workspace and does network I/O), so the unit test
 * pins only the EDT-free, side-effect-free parts: required-argument handling and the schema
 * shape. The pure provisioning core is covered by {@link VanessaBootstrapTest}.
 */
public class VanessaSetupToolTest
{
    @Test
    public void requiresProjectArgument()
    {
        VanessaSetupTool tool = new VanessaSetupTool();
        String out = tool.execute(Collections.emptyMap());
        assertTrue(out.contains("project is required")); //$NON-NLS-1$
    }

    @Test
    public void inputSchemaDeclaresCamelCaseParameters()
    {
        String schema = new VanessaSetupTool().getInputSchema();
        assertTrue(schema.contains("\"project\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"installAllure\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"project\":{\"type\":\"string\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"required\":[\"project\"]")); //$NON-NLS-1$
    }

    @Test
    public void nameMatchesRegistrationAndIsLowerCaseUnderscore()
    {
        assertTrue(new VanessaSetupTool().getName().equals("vanessa_setup")); //$NON-NLS-1$
    }
}
