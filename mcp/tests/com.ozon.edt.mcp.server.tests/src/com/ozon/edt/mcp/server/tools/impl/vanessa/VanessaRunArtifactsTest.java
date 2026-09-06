/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ozon.edt.mcp.server.tools.impl.vanessa;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Tests for {@link VanessaRunArtifacts}, the VAParams-override generator.
 * <p>
 * The run is pure file work on the project env.sh + base VAParams.json, so the
 * test redirects {@code user.home} to a temp dir, plants a minimal env.sh and
 * base VAParams, and asserts what lands in the generated override — including
 * that {@link GenerateOptions} fields map onto their VAParams keys.
 */
public class VanessaRunArtifactsTest
{
    private String oldHome;
    private Path home;
    private Path features;

    @Before
    public void setUp() throws Exception
    {
        oldHome = System.getProperty("user.home"); //$NON-NLS-1$
        home = Files.createTempDirectory("vanessa-run-artifacts-test-"); //$NON-NLS-1$
        System.setProperty("user.home", home.toString()); //$NON-NLS-1$

        features = Files.createDirectory(home.resolve("features")); //$NON-NLS-1$

        Path base = home.resolve("VAParams.json"); //$NON-NLS-1$
        Files.writeString(base,
            "{\"КаталогФич\":\"base\",\"ОтчетJUnit\":{\"КаталогВыгрузкиJUnit\":\"base-junit\"}}", //$NON-NLS-1$
            StandardCharsets.UTF_8);

        Path envDir = Files.createDirectories(
            home.resolve(".1c-tools/vanessa/projects/testproj")); //$NON-NLS-1$
        Files.writeString(envDir.resolve("env.sh"), //$NON-NLS-1$
            "PROJ=\"testproj\"\n" //$NON-NLS-1$
                + "VAPARAMS=\"" + base + "\"\n" //$NON-NLS-1$ //$NON-NLS-2$
                + "FEATURES_DIR=\"" + features + "\"\n", //$NON-NLS-1$ //$NON-NLS-2$
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
    public void testGenerateDefaultWritesOverrideAndOutDir() throws Exception
    {
        VanessaRunArtifacts.Result r = generate(null);
        assertNotNull(r);
        assertTrue(Files.isRegularFile(Paths.get(r.vaparamsOverride)));
        assertTrue(Files.isDirectory(Paths.get(r.outDir).resolve("junit"))); //$NON-NLS-1$
        assertTrue(Files.isDirectory(Paths.get(r.outDir).resolve("allure"))); //$NON-NLS-1$

        JsonObject override = readOverride(r);
        assertEquals(features.toString(), override.get("КаталогФич").getAsString()); //$NON-NLS-1$
        assertTrue(override.get("КаталогЛогов").getAsString().startsWith(r.outDir)); //$NON-NLS-1$
    }

    @Test
    public void testGenerateDefaultDoesNotInjectOptionKeys() throws Exception
    {
        JsonObject override = readOverride(generate(null));
        assertFalse(override.has("СписокТеговОтбор")); //$NON-NLS-1$
        assertFalse(override.has("КоличествоПопытокВыполненияСценария")); //$NON-NLS-1$
        assertFalse(override.has("ДелатьОтчетВФорматеАллюр")); //$NON-NLS-1$
    }

    @Test
    public void testGenerateAppliesEveryOptionKey() throws Exception
    {
        VanessaRunArtifacts.GenerateOptions o = new VanessaRunArtifacts.GenerateOptions();
        o.tagsFilter = "smoke,regress"; //$NON-NLS-1$
        o.tagsIgnore = "wip"; //$NON-NLS-1$
        o.scenarios = "Создание справочника"; //$NON-NLS-1$
        o.retries = 2;
        o.screenshotsOnError = true;
        o.asyncSteps = true;
        o.allure = true;

        JsonObject override = readOverride(generate(o));
        assertEquals("smoke,regress", override.get("СписокТеговОтбор").getAsString()); //$NON-NLS-1$
        assertEquals("wip", override.get("СписокТеговИсключение").getAsString()); //$NON-NLS-1$
        assertEquals("Создание справочника", override.get("СписокСценариевДляВыполнения").getAsString()); //$NON-NLS-1$
        assertEquals(2, override.get("КоличествоПопытокВыполненияСценария").getAsInt()); //$NON-NLS-1$
        assertTrue(override.get("ДелатьСкриншотПриВозникновенииОшибки").getAsBoolean()); //$NON-NLS-1$
        assertTrue(override.get("ВыполнятьШагиАсинхронно").getAsBoolean()); //$NON-NLS-1$
        assertTrue(override.get("ДелатьОтчетВФорматеАллюр").getAsBoolean()); //$NON-NLS-1$
    }

    @Test
    public void testReportDirReplacesOutDir() throws Exception
    {
        VanessaRunArtifacts.GenerateOptions o = new VanessaRunArtifacts.GenerateOptions();
        Path custom = home.resolve("custom-report"); //$NON-NLS-1$
        o.reportDir = custom.toString();

        VanessaRunArtifacts.Result r = generate(o);
        assertEquals(custom.toString(), r.outDir);
        assertTrue(Files.isDirectory(custom.resolve("junit"))); //$NON-NLS-1$
        assertTrue(Files.isDirectory(custom.resolve("allure"))); //$NON-NLS-1$

        JsonObject override = readOverride(r);
        assertTrue(override.get("КаталогЛогов").getAsString().startsWith(custom.toString())); //$NON-NLS-1$
    }

    @Test
    public void testScreenshotsDirResolvesUnderOutDir() throws Exception
    {
        VanessaRunArtifacts.GenerateOptions o = new VanessaRunArtifacts.GenerateOptions();
        o.screenshotsOnError = true;
        o.screenshotsDir = "screenshots"; //$NON-NLS-1$

        JsonObject override = readOverride(generate(o));
        Path expected = VanessaProjectConfig.fromProject("testproj") //$NON-NLS-1$
            .outDir().resolve("screenshots").normalize(); //$NON-NLS-1$
        assertEquals(expected,
            Paths.get(override.get("КаталогВыгрузкиСкриншотов").getAsString()).normalize()); //$NON-NLS-1$
    }

    private VanessaRunArtifacts.Result generate(VanessaRunArtifacts.GenerateOptions options) throws Exception
    {
        VanessaProjectConfig config = VanessaProjectConfig.fromProject("testproj"); //$NON-NLS-1$
        assertNotNull(config);
        return VanessaRunArtifacts.generate(config, features.toString(), options);
    }

    private static JsonObject readOverride(VanessaRunArtifacts.Result r) throws Exception
    {
        return JsonParser.parseString(
            Files.readString(Paths.get(r.vaparamsOverride), StandardCharsets.UTF_8))
            .getAsJsonObject();
    }
}
