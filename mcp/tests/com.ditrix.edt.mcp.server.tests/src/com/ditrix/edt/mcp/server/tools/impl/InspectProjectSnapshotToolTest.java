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

public class InspectProjectSnapshotToolTest
{
    private static final String UNKNOWN_ID = "02580b1e-6762-4c9d-9bf1-774473087bfa";
    private final InspectProjectSnapshotTool tool = new InspectProjectSnapshotTool();

    @Test
    public void describesCursorQueryAndTheResultPage()
    {
        JsonObject schema = json(tool.getInputSchema());
        assertEquals(Set.of("snapshotId", "view", "entryId", "compareTo", "cursor", "limit"),
            schema.getAsJsonObject("properties").keySet());
        assertEquals(2, schema.getAsJsonArray("required").size());
        assertEquals(ResponseType.JSON, tool.getResponseType());
        assertTrue(json(tool.getOutputSchema()).getAsJsonObject("properties").has("nextCursor"));
    }

    @Test
    public void rejectsInvalidIdsOutOfRangeLimitsAndUntrustedTransport()
    {
        String invalid = tool.execute(Map.of("snapshotId", "C:\\private\\source.bsl", "view", "diff"));
        assertFalse(invalid.contains("private"));
        assertTrue(json(invalid).get("error").getAsString().contains("UUIDs"));
        String overLimit = tool.execute(Map.of("snapshotId", UNKNOWN_ID, "view", "diff", "limit", "21"));
        assertTrue(json(overLimit).get("error").getAsString().contains("integer from 1 to 20"));
        String denied = tool.execute(Map.of("snapshotId", UNKNOWN_ID, "view", "manifest"));
        assertFalse(json(denied).get("success").getAsBoolean());
        assertTrue(json(denied).get("error").getAsString().contains("unavailable for this caller"));
    }

    private static JsonObject json(String value)
    {
        return JsonParser.parseString(value).getAsJsonObject();
    }
}
