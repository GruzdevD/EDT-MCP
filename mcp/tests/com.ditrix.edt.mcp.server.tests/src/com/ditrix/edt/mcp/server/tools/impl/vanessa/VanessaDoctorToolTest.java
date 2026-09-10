/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl.vanessa;

import static org.junit.Assert.assertTrue;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.Test;

/**
 * Tests the pure (EDT-free) {@link VanessaDoctorTool#render} Markdown and the tool's
 * required-argument contract. The workspace-resolving execute() path is exercised in EDT.
 */
public class VanessaDoctorToolTest
{
    @Test
    public void renderReportsReadyWhenAllPrerequisitesMet()
    {
        VanessaBootstrap.Doctor ready =
            new VanessaBootstrap.Doctor(true, true, true, true, false, false);
        String md = VanessaDoctorTool.render("afm", ready); //$NON-NLS-1$
        assertTrue(md.contains("**Ready:**")); //$NON-NLS-1$
        assertTrue(md.contains("afm")); //$NON-NLS-1$
    }

    @Test
    public void renderReportsNotReadyAndPointsAtSetup()
    {
        VanessaBootstrap.Doctor missing =
            new VanessaBootstrap.Doctor(false, false, false, false, false, false);
        String md = VanessaDoctorTool.render("afm", missing); //$NON-NLS-1$
        assertTrue(md.contains("**Not ready.**")); //$NON-NLS-1$
        assertTrue(md.contains("vanessa_setup")); //$NON-NLS-1$
    }

    @Test
    public void requiresProjectArgument()
    {
        VanessaDoctorTool tool = new VanessaDoctorTool();
        String out = tool.execute(Collections.emptyMap());
        assertTrue(out.contains("project is required")); //$NON-NLS-1$
    }

    @Test
    public void inputSchemaDeclaresCamelCaseProject()
    {
        assertTrue(new VanessaDoctorTool().getInputSchema().contains("\"project\"")); //$NON-NLS-1$
    }

    @Test
    public void executesWithProjectArgumentNothingTooHeavy()
    {
        // projectRoot is null in a plain (non-EDT) unit run, so doctor() returns a "missing"
        // report without touching the network or the workspace — the call must still succeed.
        Map<String, String> params = new LinkedHashMap<>();
        params.put("project", "afm"); //$NON-NLS-1$
        String out = new VanessaDoctorTool().execute(params);
        assertTrue(out.contains("# Vanessa doctor: afm")); //$NON-NLS-1$
    }
}
