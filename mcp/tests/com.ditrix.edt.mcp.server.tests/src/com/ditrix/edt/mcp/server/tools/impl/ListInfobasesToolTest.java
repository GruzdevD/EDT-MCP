/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.ditrix.edt.mcp.server.tools.IMcpTool.ResponseType;

/**
 * Tests for {@link ListInfobasesTool}.
 * <p>
 * Covers tool metadata, the input/output schemas, the read-only claim
 * ({@code connectsToInfobase() == false}) and the pure folder-filter decision
 * ({@link ListInfobasesTool#matchesFolder}). The EDT boundary is
 * {@code execute()}'s call to
 * {@code Activator.getDefault().getInfobaseManager()}, which needs a live
 * platform-services bundle and is covered by the E2E suite — metadata and the
 * filter are the parts reachable without one.
 */
public class ListInfobasesToolTest
{
    @Test
    public void testName()
    {
        assertEquals("list_infobases", new ListInfobasesTool().getName()); //$NON-NLS-1$
    }

    @Test
    public void testNameConstantMatchesGetter()
    {
        ListInfobasesTool tool = new ListInfobasesTool();
        assertEquals(ListInfobasesTool.NAME, tool.getName());
    }

    @Test
    public void testResponseTypeIsJson()
    {
        assertEquals(ResponseType.JSON, new ListInfobasesTool().getResponseType());
    }

    @Test
    public void testDoesNotConnectToInfobase()
    {
        // The tool only reads the in-memory registration registry; it must not
        // claim to open an infobase connection (which would arm the #194 auth
        // dialog machinery for a pure listing).
        assertFalse(new ListInfobasesTool().connectsToInfobase());
    }

    @Test
    public void testDescriptionIsNotEmpty()
    {
        String desc = new ListInfobasesTool().getDescription();
        assertNotNull(desc);
        assertTrue(desc.length() > 0);
    }

    @Test
    public void testInputSchemaDeclaresGroupName()
    {
        String schema = new ListInfobasesTool().getInputSchema();
        assertTrue("groupName must be declared in the input schema", //$NON-NLS-1$
            schema.contains("\"groupName\"")); //$NON-NLS-1$
        // The filter is optional — no required array.
        assertFalse(schema.contains("\"required\"")); //$NON-NLS-1$
    }

    @Test
    public void testOutputSchemaDeclaresCoreKeys()
    {
        String schema = new ListInfobasesTool().getOutputSchema();
        assertTrue(schema.contains("\"infobases\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"groups\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"recent\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"total\"")); //$NON-NLS-1$
    }

    @Test
    public void testBlankOrNullFilterMatchesEverything()
    {
        assertTrue(ListInfobasesTool.matchesFolder("Dev", null)); //$NON-NLS-1$
        assertTrue(ListInfobasesTool.matchesFolder("Dev", "")); //$NON-NLS-1$
        assertTrue(ListInfobasesTool.matchesFolder(null, null)); //$NON-NLS-1$
    }

    @Test
    public void testFolderFilterIsCaseInsensitive()
    {
        assertTrue(ListInfobasesTool.matchesFolder("Dev", "dev")); //$NON-NLS-1$
        assertTrue(ListInfobasesTool.matchesFolder("dev", "DEV")); //$NON-NLS-1$
        assertTrue(ListInfobasesTool.matchesFolder("Dev Bases", "DEV BASES")); //$NON-NLS-1$
    }

    @Test
    public void testFolderFilterRejectsNonMatching()
    {
        assertFalse(ListInfobasesTool.matchesFolder("Dev", "Prod")); //$NON-NLS-1$
        assertFalse(ListInfobasesTool.matchesFolder(null, "Prod")); //$NON-NLS-1$
        assertFalse(ListInfobasesTool.matchesFolder("", "Prod")); //$NON-NLS-1$
    }
}
