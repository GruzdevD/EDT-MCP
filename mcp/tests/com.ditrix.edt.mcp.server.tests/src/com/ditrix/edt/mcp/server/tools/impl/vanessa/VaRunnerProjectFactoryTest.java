/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl.vanessa;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests for {@link VaRunnerProjectFactory}: the default on-disk location and the pure
 * (EDT-free, network-free) assembly of the project skeleton — driver boilerplate plus the
 * operator's {@code VanessaAutomation} source side by side under one VA_Runner root.
 * The runtime {@code bin/} download (network) and the workspace import are not exercised here.
 */
public class VaRunnerProjectFactoryTest
{
    private String oldHome;
    private Path home;
    private Path projectRoot;
    private Path templateDir;

    @Before
    public void setUp() throws Exception
    {
        oldHome = System.getProperty("user.home"); //$NON-NLS-1$
        home = Files.createTempDirectory("va-runner-factory-home-"); //$NON-NLS-1$
        System.setProperty("user.home", home.toString()); //$NON-NLS-1$
        projectRoot = Files.createTempDirectory("va-runner-factory-root-"); //$NON-NLS-1$

        // A stand-in for the operator's VanessaAutomation EDT-source template.
        templateDir = Files.createTempDirectory("va-runner-tpl-"); //$NON-NLS-1$
        Files.writeString(templateDir.resolve("VanessaAutomation.mdo"), "mdo", StandardCharsets.UTF_8); //$NON-NLS-1$
    }

    @After
    public void tearDown() throws Exception
    {
        System.setProperty("user.home", oldHome); //$NON-NLS-1$
        deleteRecursively(projectRoot);
        deleteRecursively(templateDir);
        deleteRecursively(home);
    }

    private static void deleteRecursively(Path dir) throws Exception
    {
        if (dir != null && Files.exists(dir))
        {
            try (Stream<Path> s = Files.walk(dir))
            {
                s.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
    }

    @Test
    public void testDefaultProjectRootLivesUnderHomeVanessaLaunchers()
    {
        Path root = VaRunnerProjectFactory.defaultProjectRoot("afm"); //$NON-NLS-1$
        assertEquals(home.resolve(".1c-tools/vanessa/launchers/afm/VA_Runner"), root); //$NON-NLS-1$
    }

    @Test
    public void testAssembledProjectCarriesDriverAndSourceCompanions() throws Exception
    {
        Path destRoot = projectRoot.resolve("VA_Runner"); //$NON-NLS-1$

        // Mirror what VaRunnerProjectFactory.provision does for the network-free parts:
        // boilerplate driver first, then the operator's VanessaAutomation source.
        VanessaBootstrap.writeVaRunnerBoilerplate(destRoot, "afm"); //$NON-NLS-1$
        VanessaBootstrap.copyVanessaAutomationSource(templateDir, destRoot);

        // Driver project files and the external-objects natures.
        assertTrue(Files.isRegularFile(destRoot.resolve(".project"))); //$NON-NLS-1$
        assertTrue(Files.isRegularFile(destRoot.resolve( //$NON-NLS-1$
            "src/ExternalDataProcessors/VA_Runner/VA_Runner.mdo"))); //$NON-NLS-1$
        assertEquals("afm", Files.readString( //$NON-NLS-1$
            destRoot.resolve("DT-INF/PROJECT.PMF"), StandardCharsets.UTF_8) //$NON-NLS-1$
            .lines()
            .filter(l -> l.startsWith("Base-Project:")) //$NON-NLS-1$
            .map(l -> l.substring("Base-Project:".length()).trim()) //$NON-NLS-1$
            .findFirst()
            .orElse("")); //$NON-NLS-1$

        // The VanessaAutomation source lands as a sibling of the driver.
        assertTrue(Files.isRegularFile(destRoot.resolve( //$NON-NLS-1$
            "src/ExternalDataProcessors/VanessaAutomation/VanessaAutomation.mdo"))); //$NON-NLS-1$
    }
}
