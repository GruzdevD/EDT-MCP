/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Map;
import java.util.Set;

import org.junit.Test;

import com.ditrix.edt.mcp.server.protocol.ToolAnnotationClassifier;
import com.google.gson.JsonParser;

public class ConfirmProjectCommitToolTest
{
    private final ConfirmProjectCommitTool tool = new ConfirmProjectCommitTool();

    @Test
    public void declaresOnePlanAndTheRecoveryStatus()
    {
        var input = JsonParser.parseString(tool.getInputSchema()).getAsJsonObject();
        assertEquals(Set.of("planId"), input.getAsJsonObject("properties").keySet());
        assertEquals("planId", input.getAsJsonArray("required").get(0).getAsString());
        assertTrue(tool.getDescription().contains("get_tool_guide('confirm_project_commit')"));
        assertTrue(tool.getOutputSchema().contains("recovery_required"));
        var annotations = ToolAnnotationClassifier.classify(tool.getName());
        assertTrue(annotations.getDestructiveHint());
        assertTrue(annotations.getIdempotentHint());
    }

    @Test
    public void rejectsMalformedPlanIdBeforeGitAccess()
    {
        var result = JsonParser.parseString(tool.execute(Map.of("planId", "not-a-uuid"))).getAsJsonObject();
        assertFalse(result.get("success").getAsBoolean());
        assertTrue(result.get("error").getAsString().contains("preview_project_commit"));
    }
}
