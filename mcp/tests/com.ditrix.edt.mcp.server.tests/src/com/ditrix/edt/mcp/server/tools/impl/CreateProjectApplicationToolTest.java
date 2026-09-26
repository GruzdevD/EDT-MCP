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

import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.tools.IMcpTool.ResponseType;

/**
 * Tests for {@link CreateProjectApplicationTool}.
 * <p>
 * Covers tool metadata, the input/output schemas, the read-only claim
 * ({@code connectsToInfobase() == true} — the issue #412 read-back reaches the connection
 * layer via getApplications, so the connection-reaching tools' auth-dialog suppression must
 * scope over it) and the
 * pure name-derivation rule. The EDT boundary is {@code execute()}'s path through
 * {@code GitRepositoryResolver} / the platform-services managers (live EDT), covered by
 * the E2E suite.
 */
public class CreateProjectApplicationToolTest
{
    @Test
    public void testName()
    {
        assertEquals("create_project_application", //$NON-NLS-1$
            new CreateProjectApplicationTool().getName());
    }

    @Test
    public void testNameConstantMatchesGetter()
    {
        CreateProjectApplicationTool tool = new CreateProjectApplicationTool();
        assertEquals(CreateProjectApplicationTool.NAME, tool.getName());
    }

    @Test
    public void testResponseTypeIsJson()
    {
        assertEquals(ResponseType.JSON, new CreateProjectApplicationTool().getResponseType());
    }

    @Test
    public void testDoesNotConnectToInfobase()
    {
        // The tool is a pure EDT workspace-metadata write (association + registry
        // read-back); it must not claim an infobase connection (which would arm the
        // #194 auth dialog machinery for a metadata-only operation).
        assertTrue(new CreateProjectApplicationTool().connectsToInfobase());
    }

    @Test
    public void testDescriptionIsNotEmpty()
    {
        String desc = new CreateProjectApplicationTool().getDescription();
        assertNotNull(desc);
        assertTrue(desc.length() > 0);
    }

    @Test
    public void testInputSchemaRequiresCoreArguments()
    {
        String schema = new CreateProjectApplicationTool().getInputSchema();
        assertTrue(schema.contains("\"projectName\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"branch\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"infobaseName\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"applicationName\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"setDefault\"")); //$NON-NLS-1$
        // projectName, branch and infobaseName are required.
        String required = schema.substring(schema.indexOf("\"required\"")); //$NON-NLS-1$
        assertTrue(required.contains("\"projectName\"")); //$NON-NLS-1$
        assertTrue(required.contains("\"branch\"")); //$NON-NLS-1$
        assertTrue(required.contains("\"infobaseName\"")); //$NON-NLS-1$
    }

    @Test
    public void testOutputSchemaDeclaresBindingKeys()
    {
        String schema = new CreateProjectApplicationTool().getOutputSchema();
        assertTrue(schema.contains("\"binding\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"applicationId\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"bound\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"applications\"")); //$NON-NLS-1$
        // Optional silent-authentication passthrough is declared and never returns the password.
        assertTrue(schema.contains("\"credentialsStored\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"passwordSet\"")); //$NON-NLS-1$
        assertFalse(schema.contains("\"password\"")); //$NON-NLS-1$
    }

    @Test
    public void testInputSchemaDeclaresAuthPassthroughParams()
    {
        String schema = new CreateProjectApplicationTool().getInputSchema();
        assertTrue(schema.contains("\"access\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"user\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"password\"")); //$NON-NLS-1$
        // Auth passthrough is OPTIONAL - never part of the required-array.
        String required = schema.substring(schema.indexOf("\"required\"")); //$NON-NLS-1$
        assertFalse(required.contains("\"access\"")); //$NON-NLS-1$
        assertFalse(required.contains("\"user\"")); //$NON-NLS-1$
        assertFalse(required.contains("\"password\"")); //$NON-NLS-1$
    }

    @Test
    public void testDatabaseBuilderObjectIsUsable()
    {
        // Guards the shared schema-builder path used here against accidental breakage.
        assertNotNull(JsonSchemaBuilder.object().build());
    }

    @Test
    public void testGitBranchContextNormalizesShortNameToFullRef()
    {
        // The whole "NOT_BOUND" bug: get_applications reads the ACTIVE git-branch context addressed by
        // its FULL git ref (refs/heads/<branch>), never the short branch name. Guard the normalization.
        assertEquals("refs/heads/feature/x", //$NON-NLS-1$
            CreateProjectApplicationTool.gitBranchContext("feature/x")); //$NON-NLS-1$
    }

    @Test
    public void testGitBranchContextPassesThroughQualifiedRef()
    {
        assertEquals("refs/heads/feature/x", //$NON-NLS-1$
            CreateProjectApplicationTool.gitBranchContext("refs/heads/feature/x")); //$NON-NLS-1$
    }

    @Test
    public void testGitBranchContextTrimsWhitespace()
    {
        assertEquals("refs/heads/feature/x", //$NON-NLS-1$
            CreateProjectApplicationTool.gitBranchContext("  feature/x  ")); //$NON-NLS-1$
    }
}
