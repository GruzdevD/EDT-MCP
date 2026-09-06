/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl.vanessa;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
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

import com.ditrix.edt.mcp.server.tools.IMcpTool.ResponseType;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Tests for {@link VanessaListFeaturesTool}. Pure file work on the out-of-repo
 * feature dir; the functional case redirects {@code user.home} to a temp dir so
 * no real project config is touched.
 */
public class VanessaListFeaturesToolTest
{
    private String oldHome;
    private Path home;
    private Path features;

    @Before
    public void setUp() throws Exception
    {
        oldHome = System.getProperty("user.home"); //$NON-NLS-1$
        home = Files.createTempDirectory("vanessa-list-features-test-"); //$NON-NLS-1$
        System.setProperty("user.home", home.toString()); //$NON-NLS-1$

        features = Files.createDirectories(home.resolve("features").resolve("core")); //$NON-NLS-1$
        Files.writeString(features.resolve("001_feature.feature"), //$NON-NLS-1$
            "@smoke\nFeature: Первая фича\n  Scenario: Тест\n    Given что-то\n", //$NON-NLS-1$
            StandardCharsets.UTF_8);

        Path envDir = Files.createDirectories(
            home.resolve(".1c-tools/vanessa/projects/testproj")); //$NON-NLS-1$
        Files.writeString(envDir.resolve("env.sh"), //$NON-NLS-1$
            "PROJ=\"testproj\"\nFEATURES_DIR=\"" + home.resolve("features") + "\"\n", //$NON-NLS-1$
            StandardCharsets.UTF_8);
    }

    @After
    public void tearDown() throws Exception
    {
        System.setProperty("user.home", oldHome); //$NON-NLS-1$
        if (home != null && Files.exists(home))
        {
            try (java.util.stream.Stream<Path> s = Files.walk(home))
            {
                s.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
    }

    @Test
    public void testName()
    {
        assertEquals("vanessa_list_features", new VanessaListFeaturesTool().getName()); //$NON-NLS-1$
    }

    @Test
    public void testResponseTypeJson()
    {
        assertEquals(ResponseType.JSON, new VanessaListFeaturesTool().getResponseType());
    }

    @Test
    public void testSchemaRequiresProject()
    {
        assertTrue(new VanessaListFeaturesTool().getInputSchema().contains("project")); //$NON-NLS-1$
    }

    @Test
    public void testExecuteWithoutProjectFails()
    {
        String result = new VanessaListFeaturesTool().execute(new HashMap<>());
        assertNotNull(result);
        assertTrue(result.contains("\"success\": false")); //$NON-NLS-1$
    }

    @Test
    public void testExecuteWithUnknownProjectFails()
    {
        Map<String, String> params = new HashMap<>();
        params.put("project", "no_such_vanessa_project"); //$NON-NLS-1$
        assertTrue(new VanessaListFeaturesTool().execute(params).contains("\"success\": false")); //$NON-NLS-1$
    }

    @Test
    public void testExecuteListsFeaturesAndTags()
    {
        Map<String, String> params = new HashMap<>();
        params.put("project", "testproj"); //$NON-NLS-1$
        String result = new VanessaListFeaturesTool().execute(params);
        assertTrue(result.contains("\"success\": true")); //$NON-NLS-1$

        JsonObject o = JsonParser.parseString(result).getAsJsonObject();
        assertEquals(1, o.get("count").getAsInt()); //$NON-NLS-1$
        JsonObject first = o.getAsJsonArray("features").get(0).getAsJsonObject(); //$NON-NLS-1$
        assertEquals("Первая фича", first.get("featureName").getAsString()); //$NON-NLS-1$
        assertEquals("smoke", first.getAsJsonArray("tags").get(0).getAsString()); //$NON-NLS-1$
    }
}
