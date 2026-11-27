/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.*;
import java.util.Map;
import java.util.UUID;
import org.junit.Test;

public class StageVerifiedEntriesToolTest
{
    @Test
    public void rejectsAnEmptySelection()
    {
        var tool = new StageVerifiedEntriesTool();
        assertTrue(tool.execute(Map.of("repoId","app","snapshotId",UUID.randomUUID().toString(),"entryIds","[]"))
            .contains("distinct entry UUIDs"));
    }
}
