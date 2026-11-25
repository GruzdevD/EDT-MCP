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
import java.util.stream.Collectors;

import org.junit.Test;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

public class PreviewProjectCommitToolTest
{
    private final PreviewProjectCommitTool tool = new PreviewProjectCommitTool();

    @Test
    public void declaresTheReviewedInputsAndOutput()
    {
        var schema = JsonParser.parseString(tool.getInputSchema()).getAsJsonObject();
        var required = schema.getAsJsonArray("required").asList().stream().map(JsonElement::getAsString)
            .collect(Collectors.toSet());
        assertEquals(Set.of("snapshotId", "entryIds", "message"), required);
        assertEquals("array", schema.getAsJsonObject("properties").getAsJsonObject("entryIds").get("type").getAsString());
        assertTrue(tool.getDescription().contains("get_tool_guide('preview_project_commit')"));
        assertTrue(JsonParser.parseString(tool.getOutputSchema()).getAsJsonObject().getAsJsonObject("properties").has("treeId"));
    }

    @Test
    public void refusesInvalidEntriesBeforeLookingUpASnapshot()
    {
        var result = JsonParser.parseString(tool.execute(Map.of("snapshotId", "45950a63-08e8-4d4f-8840-bf903a5c36d4",
            "entryIds", "[123]", "message", "Reviewed message"))).getAsJsonObject();
        assertFalse(result.get("success").getAsBoolean());
        assertTrue(result.get("error").getAsString().contains("entry UUIDs"));
    }
}
