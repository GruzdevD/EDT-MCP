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
import com.ditrix.edt.mcp.server.utils.StandaloneServerSupport.ServerInfo;

/**
 * Tests for {@link RemoveStandaloneServerTool}.
 * <p>
 * Covers tool metadata, the input/output schemas, the non-connecting claim
 * ({@code connectsToInfobase() == false}) and the pure matching predicate
 * ({@link RemoveStandaloneServerTool#matches}). The EDT boundary is
 * {@code execute()}'s call to
 * {@link com.ditrix.edt.mcp.server.utils.StandaloneServerSupport#acquireService()},
 * which needs a live standalone-server bundle and is covered by the E2E suite —
 * metadata and the matcher are the parts reachable without one.
 */
public class RemoveStandaloneServerToolTest
{
    @Test
    public void testName()
    {
        assertEquals("remove_standalone_server", new RemoveStandaloneServerTool().getName()); //$NON-NLS-1$
    }

    @Test
    public void testNameConstantMatchesGetter()
    {
        RemoveStandaloneServerTool tool = new RemoveStandaloneServerTool();
        assertEquals(RemoveStandaloneServerTool.NAME, tool.getName());
    }

    @Test
    public void testResponseTypeIsJson()
    {
        assertEquals(ResponseType.JSON, new RemoveStandaloneServerTool().getResponseType());
    }

    @Test
    public void testDoesNotConnectToInfobase()
    {
        // The tool only deletes WST server registrations; it opens NO infobase
        // connection, so it must not arm the auth-dialog machinery.
        assertFalse(new RemoveStandaloneServerTool().connectsToInfobase());
    }

    @Test
    public void testDescriptionIsNotEmpty()
    {
        String desc = new RemoveStandaloneServerTool().getDescription();
        assertNotNull(desc);
        assertTrue(desc.length() > 0);
        // The description must end by pointing the client at its guide.
        assertTrue(desc.contains("get_tool_guide('remove_standalone_server')")); //$NON-NLS-1$
    }

    @Test
    public void testInputSchemaDeclaresCoreParams()
    {
        String schema = new RemoveStandaloneServerTool().getInputSchema();
        assertTrue("projectName must be declared", schema.contains("\"projectName\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("infobaseName must be declared", schema.contains("\"infobaseName\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("confirm must be declared", schema.contains("\"confirm\"")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testOutputSchemaDeclaresCoreKeys()
    {
        String schema = new RemoveStandaloneServerTool().getOutputSchema();
        assertTrue(schema.contains("\"matchedServers\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"allServers\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"removedServers\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"remainingServers\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"confirmationRequired\"")); //$NON-NLS-1$
    }

    @Test
    public void testMatchesByModuleNameCaseInsensitive()
    {
        ServerInfo info = new ServerInfo(null, "srv", null, "Инвест", null); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(RemoveStandaloneServerTool.matches(info, "Инвест", "mom")); //$NON-NLS-1$
        assertTrue(RemoveStandaloneServerTool.matches(info, "инвест", "mom")); //$NON-NLS-1$
    }

    @Test
    public void testMatchesByInfobaseIdFallback()
    {
        // A server whose module name differs from the base name still matches
        // when its raw infobaseId equals the target name (low-fidelity fallback).
        ServerInfo info = new ServerInfo(null, "srv", null, "ServerApplication.Инвест", "Инвест"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertTrue(RemoveStandaloneServerTool.matches(info, "Инвест", "mom")); //$NON-NLS-1$
    }

    @Test
    public void testDoesNotMatchUnrelatedServer()
    {
        ServerInfo info = new ServerInfo(null, "srv", null, "ДругаяБаза", "other-id"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertFalse(RemoveStandaloneServerTool.matches(info, "Инвест", "mom")); //$NON-NLS-1$
    }

    @Test
    public void testNullInfoNeverMatches()
    {
        assertFalse(RemoveStandaloneServerTool.matches(null, "Инвест", "mom")); //$NON-NLS-1$
    }
}
