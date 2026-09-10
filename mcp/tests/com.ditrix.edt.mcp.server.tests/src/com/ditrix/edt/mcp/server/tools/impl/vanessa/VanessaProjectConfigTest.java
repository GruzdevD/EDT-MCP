/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl.vanessa;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests for {@link VanessaProjectConfig} env.sh parsing.
 * <p>
 * The parser strip-model is simplest to pin with a real file layout: {@code user.home}
 * is redirected to a temp dir and an env.sh is planted in the project location, exactly
 * how the operator keeps it. The regression under test is the trailing inline comment
 * (e.g. {@code EDT_PROJ="VA_Runner" # comment}): comments must be removed BEFORE the
 * surrounding quotes are stripped, or the value keeps them and downstream resolution
 * fails with {@code Project not found: "VA_Runner"}.
 */
public class VanessaProjectConfigTest
{
    private String oldHome;
    private Path home;

    @Before
    public void setUp() throws Exception
    {
        oldHome = System.getProperty("user.home"); //$NON-NLS-1$
        home = Files.createTempDirectory("vanessa-project-config-test-"); //$NON-NLS-1$
        System.setProperty("user.home", home.toString()); //$NON-NLS-1$
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
    public void testParsesQuotedValuesWithTrailingComments() throws Exception
    {
        writeEnv(
            "PROJ=\"mom\"\n" //$NON-NLS-1$
                + "EDT_LAUNCH=\"mom Тонкий клиент Invest\"  # runtime-client launch config\n" //$NON-NLS-1$
                + "EDT_PROJ=\"VA_Runner\"  # external-objects project (linked, outside git)\n" //$NON-NLS-1$
                + "EDT_OBJECT=\"ExternalDataProcessor.VanessaAutomation\"  # full VA processor\n" //$NON-NLS-1$
                + "DB_USER=\"Администратор\"  # no password\n"); //$NON-NLS-1$

        VanessaProjectConfig config = VanessaProjectConfig.fromProject("mom"); //$NON-NLS-1$
        assertNotNull(config);
        // quotes must be stripped even though a trailing " # comment" follows the value
        assertEquals("mom Тонкий клиент Invest", config.edtLaunch); //$NON-NLS-1$
        assertEquals("VA_Runner", config.edtProj); //$NON-NLS-1$
        assertEquals("ExternalDataProcessor.VanessaAutomation", config.edtObject); //$NON-NLS-1$
        assertEquals("Администратор", config.dbUser); //$NON-NLS-1$
    }

    @Test
    public void testParsesUnquotedAndCommentFreeValues() throws Exception
    {
        writeEnv(
            "PROJ=mom\n" //$NON-NLS-1$
                + "EDT_PROJ=VA_Runner\n"); //$NON-NLS-1$

        VanessaProjectConfig config = VanessaProjectConfig.fromProject("mom"); //$NON-NLS-1$
        assertNotNull(config);
        assertEquals("mom", config.project); //$NON-NLS-1$
        assertEquals("VA_Runner", config.edtProj); //$NON-NLS-1$
    }

    @Test
    public void testResolveEnvCandidatesPreferenceAndFallback()
    {
        // A resolvable EDT project root prefers the provisioned .vanessa/env.sh.
        List<Path> withRoot =
            VanessaProjectConfig.resolveEnvCandidates(Paths.get("/proj/afm"), "afm", "/home/u"); //$NON-NLS-1$
        assertNotNull(withRoot);
        assertEquals(2, withRoot.size());
        assertEquals(Paths.get("/proj/afm/.vanessa/env.sh"), withRoot.get(0)); //$NON-NLS-1$
        assertEquals(Paths.get("/home/u/.1c-tools/vanessa/projects/afm/env.sh"), withRoot.get(1)); //$NON-NLS-1$

        // No project root (headless / non-EDT project key) -> only the legacy candidate, so
        // previously-configured projects keep resolving exactly where they always did.
        List<Path> noRoot =
            VanessaProjectConfig.resolveEnvCandidates(null, "afm", "/home/u"); //$NON-NLS-1$
        assertEquals(1, noRoot.size());
        assertEquals(Paths.get("/home/u/.1c-tools/vanessa/projects/afm/env.sh"), noRoot.get(0)); //$NON-NLS-1$
    }

    private void writeEnv(String content) throws Exception
    {
        Path envDir = Files.createDirectories(
            home.resolve(".1c-tools/vanessa/projects/mom")); //$NON-NLS-1$
        Files.writeString(envDir.resolve("env.sh"), content, StandardCharsets.UTF_8);
    }
}
