/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ozon.edt.mcp.server.tools.impl.vanessa;

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

import com.ozon.edt.mcp.server.tools.IMcpTool.ResponseType;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Tests for {@link VanessaGetFeatureTool}: static contract plus parse of a
 * temporary {@code .feature} file (pure file work, no EDT/home redirect).
 */
public class VanessaGetFeatureToolTest
{
    private Path tmpDir;

    @Before
    public void setUp() throws Exception
    {
        tmpDir = Files.createTempDirectory("vanessa-get-feature-test-"); //$NON-NLS-1$
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
        assertEquals("vanessa_get_feature", new VanessaGetFeatureTool().getName()); //$NON-NLS-1$
    }

    @Test
    public void testResponseTypeJson()
    {
        assertEquals(ResponseType.JSON, new VanessaGetFeatureTool().getResponseType());
    }

    @Test
    public void testExecuteWithoutPathFails()
    {
        assertTrue(new VanessaGetFeatureTool().execute(new HashMap<>())
            .contains("\"success\":false")); //$NON-NLS-1$
    }

    @Test
    public void testExecuteWithMissingFileFails()
    {
        Map<String, String> params = new HashMap<>();
        params.put("path", "/no/such/path.feature"); //$NON-NLS-1$
        assertTrue(new VanessaGetFeatureTool().execute(params)
            .contains("\"success\":false")); //$NON-NLS-1$
    }

    @Test
    public void testParsesFeatureScenariosAndRaw() throws Exception
    {
        Path f = tmpDir.resolve("sample.feature"); //$NON-NLS-1$
        Files.writeString(f,
            "@smoke\nFeature: Демо\n" //$NON-NLS-1$
                + "  Scenario: Первый\n    Given шаг\n    Then шаг2\n" //$NON-NLS-1$
                + "  @regress\n  Scenario: Второй\n    When шаг3\n", //$NON-NLS-1$
            StandardCharsets.UTF_8);

        Map<String, String> params = new HashMap<>();
        params.put("path", f.toString()); //$NON-NLS-1$
        String result = new VanessaGetFeatureTool().execute(params);
        assertTrue(result.contains("\"success\":true")); //$NON-NLS-1$

        JsonObject o = JsonParser.parseString(result).getAsJsonObject();
        assertEquals("Демо", o.get("feature").getAsString()); //$NON-NLS-1$
        assertEquals("smoke", o.getAsJsonArray("tags").get(0).getAsString()); //$NON-NLS-1$
        assertEquals(2, o.getAsJsonArray("scenarios").size()); //$NON-NLS-1$
        JsonObject first = o.getAsJsonArray("scenarios").get(0).getAsJsonObject(); //$NON-NLS-1$
        assertEquals("Первый", first.get("name").getAsString()); //$NON-NLS-1$
        assertEquals(2, first.getAsJsonArray("steps").size()); //$NON-NLS-1$
    }

    /** Full Russian gherkin (1C:Enterprise / Vanessa Automation) must parse too. */
    @Test
    public void testParsesRussianGherkinKeywords() throws Exception
    {
        Path f = tmpDir.resolve("ru.feature"); //$NON-NLS-1$
        Files.writeString(f,
            "#language: ru\n@smoke\nФункционал: Демо RU\n" //$NON-NLS-1$
                + "  Контекст:\n    Дано пропущено\n" //$NON-NLS-1$
                + "  Сценарий: Первый RU\n    Когда я делаю шаг\n    И ещё шаг\n    Тогда проверю\n", //$NON-NLS-1$
            StandardCharsets.UTF_8);

        Map<String, String> params = new HashMap<>();
        params.put("path", f.toString()); //$NON-NLS-1$
        String result = new VanessaGetFeatureTool().execute(params);
        assertTrue(result.contains("\"success\":true")); //$NON-NLS-1$

        JsonObject o = JsonParser.parseString(result).getAsJsonObject();
        assertEquals("Демо RU", o.get("feature").getAsString()); //$NON-NLS-1$
        assertEquals("smoke", o.getAsJsonArray("tags").get(0).getAsString()); //$NON-NLS-1$
        assertEquals(1, o.getAsJsonArray("scenarios").size()); //$NON-NLS-1$
        JsonObject sc = o.getAsJsonArray("scenarios").get(0).getAsJsonObject(); //$NON-NLS-1$
        assertEquals("Первый RU", sc.get("name").getAsString()); //$NON-NLS-1$
        assertEquals(3, sc.getAsJsonArray("steps").size()); //$NON-NLS-1$
    }
}
