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
 * Tests for {@link DeleteProjectApplicationTool}.
 * <p>
 * Covers the tool metadata, the input/output schemas (including the required-array: only
 * {@code projectName} and {@code applicationId} are required — {@code confirm} must NOT be),
 * the infobase-connection claim ({@code connectsToInfobase() == true} — EDT's application
 * deletion reaches the connection layer) and the guide pointer. The actual deletion
 * ({@code IApplicationManager.delete}) runs against live project/application state and is
 * covered by the E2E suite.
 */
public class DeleteProjectApplicationToolTest
{
    @Test
    public void testName()
    {
        assertEquals("delete_project_application", new DeleteProjectApplicationTool().getName()); //$NON-NLS-1$
    }

    @Test
    public void testNameConstantMatchesGetter()
    {
        DeleteProjectApplicationTool tool = new DeleteProjectApplicationTool();
        assertEquals(DeleteProjectApplicationTool.NAME, tool.getName());
    }

    @Test
    public void testResponseTypeIsJson()
    {
        assertEquals(ResponseType.JSON, new DeleteProjectApplicationTool().getResponseType());
    }

    @Test
    public void testConnectsToInfobase()
    {
        // EDT's IApplicationManager.delete cleans up and reaches the application/connection
        // layer (e.g. a running standalone server) - must claim an infobase connection.
        assertTrue(new DeleteProjectApplicationTool().connectsToInfobase());
    }

    @Test
    public void testDescriptionIsNotEmptyAndPointsToGuide()
    {
        String desc = new DeleteProjectApplicationTool().getDescription();
        assertNotNull(desc);
        assertTrue(desc.length() > 0);
        assertTrue(desc.contains("get_tool_guide('delete_project_application')")); //$NON-NLS-1$
    }

    @Test
    public void testInputSchemaDeclaresArgumentsAndRequired()
    {
        String schema = new DeleteProjectApplicationTool().getInputSchema();
        assertTrue(schema.contains("\"projectName\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"applicationId\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"confirm\"")); //$NON-NLS-1$
        String required = schema.substring(schema.indexOf("\"required\"")); //$NON-NLS-1$
        assertTrue(required.contains("\"projectName\"")); //$NON-NLS-1$
        assertTrue(required.contains("\"applicationId\"")); //$NON-NLS-1$
        // confirm must NOT be required.
        assertFalse(required.contains("\"confirm\"")); //$NON-NLS-1$
    }

    @Test
    public void testOutputSchemaDeclaresCoreKeys()
    {
        String schema = new DeleteProjectApplicationTool().getOutputSchema();
        assertTrue(schema.contains("\"action\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"confirmationRequired\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"removed\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"remainingApplications\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"applicationId\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"applicationName\"")); //$NON-NLS-1$
    }
}
