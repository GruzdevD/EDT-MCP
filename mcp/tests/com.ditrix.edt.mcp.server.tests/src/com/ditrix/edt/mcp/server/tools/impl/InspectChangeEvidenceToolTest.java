/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.*;
import java.util.Map;
import org.junit.Test;

public class InspectChangeEvidenceToolTest
{
    @Test public void rejectsMalformedJobIdWithoutEchoingInput()
    {
        var result = new InspectChangeEvidenceTool().execute(Map.of("repoId","app","projectName","Base",
            "branch","refs/heads/main","changeId","change","view","job","jobId","private-path"));
        assertTrue(result.contains("false"));
        assertFalse(result.contains("private-path"));
    }
}
