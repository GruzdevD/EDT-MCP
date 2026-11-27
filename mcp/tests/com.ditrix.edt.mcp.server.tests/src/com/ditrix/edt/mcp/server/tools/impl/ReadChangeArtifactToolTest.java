/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.*;
import java.util.Map;
import org.junit.Test;

public class ReadChangeArtifactToolTest
{
    @Test
    public void refusesArbitraryFileArguments()
    {
        var result = new ReadChangeArtifactTool().execute(Map.of("repoId","r","projectName","P","branch","refs/heads/main",
            "changeId","change","view","read","kind","delta","itemId","../private"));
        assertTrue(result.contains("false"));
        assertFalse(result.contains("private"));
    }
}
