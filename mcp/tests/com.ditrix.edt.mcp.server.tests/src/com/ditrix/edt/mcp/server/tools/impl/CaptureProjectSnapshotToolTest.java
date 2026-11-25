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

import com.ditrix.edt.mcp.server.tools.IMcpTool.ResponseType;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class CaptureProjectSnapshotToolTest
{
    private final CaptureProjectSnapshotTool tool = new CaptureProjectSnapshotTool();

    @Test
    public void describesExactProjectNamesAndTheReturnedId()
    {
        JsonObject schema = json(tool.getInputSchema());
        assertEquals(Set.of("projectName", "includedProjects", "forYaxunit"),
            schema.getAsJsonObject("properties").keySet());
        assertEquals("projectName", schema.getAsJsonArray("required").get(0).getAsString());
        assertEquals(ResponseType.JSON, tool.getResponseType());
        assertTrue(json(tool.getOutputSchema()).getAsJsonObject("properties").has("snapshotId"));
        assertTrue(json(tool.getOutputSchema()).getAsJsonObject("properties").has("yaxunitReady"));
    }

    @Test
    public void requiresProtectedTransportAndRejectsAnInvalidExtensionList()
    {
        assertFalse(json(tool.execute(Map.of())).get("success").getAsBoolean());
        String invalid = tool.execute(Map.of("projectName", "Example", "includedProjects", "[123]"));
        assertTrue(json(invalid).get("error").getAsString().contains("includedProjects"));
        String denied = tool.execute(Map.of("projectName", "Example"));
        assertFalse(json(denied).get("success").getAsBoolean());
        assertTrue(json(denied).get("error").getAsString().contains("unavailable for this caller"));
    }

    private static JsonObject json(String value)
    {
        return JsonParser.parseString(value).getAsJsonObject();
    }
}
