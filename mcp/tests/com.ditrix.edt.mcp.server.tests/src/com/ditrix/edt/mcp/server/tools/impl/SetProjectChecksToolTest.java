/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

import com.ditrix.edt.mcp.server.tools.IMcpTool.ResponseType;

/**
 * Tests for {@link SetProjectChecksTool}.
 * <p>
 * Covers tool metadata, the input/output schemas, the read-only claim
 * ({@code connectsToInfobase() == false} — a metadata preference write, not an infobase
 * connection) and the pure boolean-argument parsing
 * ({@link SetProjectChecksTool#parseDisableMassiveChecks}). The EDT boundary is
 * {@code execute()}'s {@code ProjectContext}/{@code CheckProcessSupport} path, which
 * needs a live project and the check service and is covered by the E2E suite.
 */
public class SetProjectChecksToolTest
{
    @Test
    public void testName()
    {
        assertEquals("set_project_checks", new SetProjectChecksTool().getName()); //$NON-NLS-1$
    }

    @Test
    public void testNameConstantMatchesGetter()
    {
        SetProjectChecksTool tool = new SetProjectChecksTool();
        assertEquals(SetProjectChecksTool.NAME, tool.getName());
    }

    @Test
    public void testResponseTypeIsJson()
    {
        assertEquals(ResponseType.JSON, new SetProjectChecksTool().getResponseType());
    }

    @Test
    public void testDoesNotConnectToInfobase()
    {
        // A workspace preference write — must not claim an infobase connection.
        assertFalse(new SetProjectChecksTool().connectsToInfobase());
    }

    @Test
    public void testDescriptionIsNotEmpty()
    {
        String desc = new SetProjectChecksTool().getDescription();
        assertNotNull(desc);
        assertTrue(desc.length() > 0);
    }

    @Test
    public void testInputSchemaDeclaresArguments()
    {
        String schema = new SetProjectChecksTool().getInputSchema();
        assertTrue(schema.contains("\"projectName\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"disableMassiveChecks\"")); //$NON-NLS-1$
        String required = schema.substring(schema.indexOf("\"required\"")); //$NON-NLS-1$
        assertTrue(required.contains("\"projectName\"")); //$NON-NLS-1$
        assertTrue(required.contains("\"disableMassiveChecks\"")); //$NON-NLS-1$
    }

    @Test
    public void testOutputSchemaDeclaresCoreKeys()
    {
        String schema = new SetProjectChecksTool().getOutputSchema();
        assertTrue(schema.contains("\"disableMassiveChecks\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"changed\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"project\"")); //$NON-NLS-1$
    }

    @Test
    public void testParseAcceptsTruthySpellings()
    {
        Map<String, String> p = new HashMap<>();
        p.put("disableMassiveChecks", "true"); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(Boolean.TRUE, SetProjectChecksTool.parseDisableMassiveChecks(p));
        p.put("disableMassiveChecks", "1"); //$NON-NLS-1$
        assertEquals(Boolean.TRUE, SetProjectChecksTool.parseDisableMassiveChecks(p));
        p.put("disableMassiveChecks", "YES"); //$NON-NLS-1$
        assertEquals(Boolean.TRUE, SetProjectChecksTool.parseDisableMassiveChecks(p));
    }

    @Test
    public void testParseAcceptsFalsySpellings()
    {
        Map<String, String> p = new HashMap<>();
        p.put("disableMassiveChecks", "false"); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(Boolean.FALSE, SetProjectChecksTool.parseDisableMassiveChecks(p));
        p.put("disableMassiveChecks", "0"); //$NON-NLS-1$
        assertEquals(Boolean.FALSE, SetProjectChecksTool.parseDisableMassiveChecks(p));
        p.put("disableMassiveChecks", "no"); //$NON-NLS-1$
        assertEquals(Boolean.FALSE, SetProjectChecksTool.parseDisableMassiveChecks(p));
    }

    @Test
    public void testParseRejectsMissingBlankAndInvalid()
    {
        assertNull(SetProjectChecksTool.parseDisableMassiveChecks(null));
        assertNull(SetProjectChecksTool.parseDisableMassiveChecks(new HashMap<>()));
        Map<String, String> p = new HashMap<>();
        p.put("disableMassiveChecks", ""); //$NON-NLS-1$
        assertNull(SetProjectChecksTool.parseDisableMassiveChecks(p));
        p.put("disableMassiveChecks", "banana"); //$NON-NLS-1$
        assertNull(SetProjectChecksTool.parseDisableMassiveChecks(p));
        p.put("disableMassiveChecks", "  "); //$NON-NLS-1$
        assertNull(SetProjectChecksTool.parseDisableMassiveChecks(p));
    }
}
