/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ozon.edt.mcp.server.tools.impl.vanessa;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.ozon.edt.mcp.server.tools.IMcpTool.ResponseType;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Tests for {@link VanessaGetLogTool}: static contract plus paging over a
 * temporary log file (pure file work).
 */
public class VanessaGetLogToolTest
{
    private Path tmpDir;

    @Before
    public void setUp() throws Exception
    {
        tmpDir = Files.createTempDirectory("vanessa-get-log-test-"); //$NON-NLS-1$
    }

    @After
    public void tearDown() throws Exception
    {
        if (tmpDir != null && Files.exists(tmpDir))
        {
            try (java.util.stream.Stream<Path> s = Files.walk(tmpDir))
            {
                s.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
    }

    @Test
    public void testName()
    {
        assertEquals("vanessa_get_log", new VanessaGetLogTool().getName()); //$NON-NLS-1$
    }

    @Test
    public void testResponseTypeJson()
    {
        assertEquals(ResponseType.JSON, new VanessaGetLogTool().getResponseType());
    }

    @Test
    public void testExecuteWithoutSelectorsFails()
    {
        assertTrue(new VanessaGetLogTool().execute(new HashMap<>()) //$NON-NLS-1$
            .contains("\"success\": false")); //$NON-NLS-1$
    }

    @Test
    public void testExecuteWithMissingFileFails()
    {
        Map<String, String> params = new HashMap<>();
        params.put("logPath", "/no/such/log"); //$NON-NLS-1$
        assertTrue(new VanessaGetLogTool().execute(params) //$NON-NLS-1$
            .contains("\"success\": false")); //$NON-NLS-1$
    }

    @Test
    public void testPagingReturnsWindowAndMore() throws Exception
    {
        Path log = tmpDir.resolve("BDD.log"); //$NON-NLS-1$
        Files.writeString(log,
            "line0\nline1\nline2\nline3\nline4\nline5\n", StandardCharsets.UTF_8); //$NON-NLS-1$

        Map<String, String> params = new HashMap<>();
        params.put("logPath", log.toString()); //$NON-NLS-1$
        params.put("limit", "4"); //$NON-NLS-1$

        JsonObject first = JsonParser.parseString(new VanessaGetLogTool().execute(params)) //$NON-NLS-1$
            .getAsJsonObject();
        assertEquals(6, first.get("totalLines").getAsInt()); //$NON-NLS-1$
        assertEquals(4, first.get("returned").getAsInt()); //$NON-NLS-1$
        assertTrue(first.get("more").getAsBoolean()); //$NON-NLS-1$
        assertEquals("line3", first.getAsJsonArray("lines").get(3).getAsString()); //$NON-NLS-1$

        params.put("offsetLines", "4"); //$NON-NLS-1$
        JsonObject second = JsonParser.parseString(new VanessaGetLogTool().execute(params)) //$NON-NLS-1$
            .getAsJsonObject();
        JsonArray lines = second.getAsJsonArray("lines");
        assertEquals(2, lines.size()); //$NON-NLS-1$
        assertFalse(second.get("more").getAsBoolean()); //$NON-NLS-1$
        assertEquals("line4", lines.get(0).getAsString()); //$NON-NLS-1$
        assertEquals("line5", lines.get(1).getAsString()); //$NON-NLS-1$
    }
}
