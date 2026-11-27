/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.*;
import java.util.Map;
import org.junit.Test;

public class WriteChangeArtifactToolTest
{
    @Test
    public void refusesBoundaryOverwrite()
    {
        var result = new WriteChangeArtifactTool().execute(Map.of("repoId","r","projectName","P","changeId","change",
            "branch","refs/heads/main","kind","boundary","itemId","one","operation","put","expectedRevision","old","base64","QQ=="));
        assertTrue(result.contains("false"));
    }
}
