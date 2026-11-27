/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.*;
import java.util.Map;
import org.junit.Test;
import com.ditrix.edt.mcp.server.protocol.McpProtocolHandler;

public class InspectProjectVersionToolTest
{
    @Test
    public void rejectsHostPathsAndExcludesThePayloadFromTransportTrace()
    {
        var tool = new InspectProjectVersionTool();
        String error = tool.execute(Map.of("repoId", "repo", "projectName", "p", "snapshotId", "private/path",
            "side", "disk", "view", "manifest"));
        assertTrue(error.contains("snapshot UUID"));
        assertFalse(error.contains("private"));
        assertTrue(McpProtocolHandler.isSnapshotCall("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\","
            + "\"params\":{\"name\":\"inspect_project_version\",\"arguments\":{\"literal\":\"private\"}}}"));
        assertTrue(tool.getOutputSchema().contains("complete"));
    }
}
