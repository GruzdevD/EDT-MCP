/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.*;
import java.util.Map;
import org.junit.Test;

public class ResolveProjectReviewToolTest
{
    @Test
    public void rejectsArbitraryRevisionExpressions()
    {
        var tool = new ResolveProjectReviewTool();
        var denied = tool.execute(Map.of("repoId", "r", "projectName", "p", "branch", "refs/heads/main",
            "changeId", "feature", "expectedFinal", "HEAD~2"));
        assertTrue(denied.contains("full commit SHA"));
        assertTrue(tool.getOutputSchema().contains("observedHead"));
    }
}
