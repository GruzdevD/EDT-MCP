/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ozon.edt.mcp.server.tools.impl.vanessa;

import static org.junit.Assert.assertEquals;
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
 * Tests for {@link VanessaGetArtifactsTool}: static contract plus enumeration
 * and {@code kind} classification over a temporary out dir (pure file work).
 */
public class VanessaGetArtifactsToolTest
{
    private Path tmpDir;

    @Before
    public void setUp() throws Exception
    {
        tmpDir = Files.createTempDirectory("vanessa-get-artifacts-test-"); //$NON-NLS-1$
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
        assertEquals("vanessa_get_artifacts", new VanessaGetArtifactsTool().getName()); //$NON-NLS-1$
    }

    @Test
    public void testResponseTypeJson()
    {
        assertEquals(ResponseType.JSON, new VanessaGetArtifactsTool().getResponseType());
    }

    @Test
    public void testExecuteWithoutSelectorsFails()
    {
        assertTrue(new VanessaGetArtifactsTool().execute(new HashMap<>()) //$NON-NLS-1$
            .contains("\"success\": false")); //$NON-NLS-1$
    }

    @Test
    public void testExecuteWithMissingDirFails()
    {
        Map<String, String> params = new HashMap<>();
        params.put("outDir", "/no/such/dir"); //$NON-NLS-1$
        assertTrue(new VanessaGetArtifactsTool().execute(params) //$NON-NLS-1$
            .contains("\"success\": false")); //$NON-NLS-1$
    }

    @Test
    public void testClassifiesArtifacts() throws Exception
    {
        Path out = Files.createDirectories(tmpDir.resolve("out")); //$NON-NLS-1$
        Files.writeString(out.resolve("junit.xml"), "<x/>", StandardCharsets.UTF_8); //$NON-NLS-1$
        Files.createDirectories(out.resolve("allure")); //$NON-NLS-1$
        Files.writeString(out.resolve("allure/shot.png"), "png", StandardCharsets.UTF_8); //$NON-NLS-1$
        Files.writeString(out.resolve("BDD.log"), "run", StandardCharsets.UTF_8); //$NON-NLS-1$

        Map<String, String> params = new HashMap<>();
        params.put("outDir", out.toString()); //$NON-NLS-1$
        JsonObject o = JsonParser.parseString( //$NON-NLS-1$
            new VanessaGetArtifactsTool().execute(params)).getAsJsonObject();

        assertTrue(o.get("success").getAsBoolean()); //$NON-NLS-1$
        assertEquals(3, o.get("count").getAsInt()); //$NON-NLS-1$
        JsonArray artifacts = o.getAsJsonArray("artifacts");
        assertEquals("junit", kindOf(artifacts, "junit.xml")); //$NON-NLS-1$
        assertEquals("allure", kindOf(artifacts, "allure/shot.png")); //$NON-NLS-1$
        assertEquals("log", kindOf(artifacts, "BDD.log")); //$NON-NLS-1$
    }

    private static String kindOf(JsonArray artifacts, String suffix)
    {
        for (int i = 0; i < artifacts.size(); i++)
        {
            JsonObject item = artifacts.get(i).getAsJsonObject();
            if (item.get("path").getAsString().endsWith(suffix)) //$NON-NLS-1$
            {
                return item.get("kind").getAsString(); //$NON-NLS-1$
            }
        }
        return "none"; //$NON-NLS-1$
    }
}
