/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ozon.edt.mcp.server.tools.impl.selfupdate;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests for {@link PluginSelfUpdate} — the headless-testable mechanics behind the
 * git-based self-update tools: version parse/compare, jar discovery, and the
 * {@code bundles.info} / p2-pool deploy steps. The user-home git fetch and the
 * running EDT's install location are not exercisable here and are covered live.
 */
public class PluginSelfUpdateTest
{
    private Path tmp;

    @Before
    public void setUp() throws IOException
    {
        tmp = Files.createTempDirectory("selfupdate-test"); //$NON-NLS-1$
    }

    @After
    public void tearDown() throws IOException
    {
        deleteRecursively(tmp);
    }

    private static void deleteRecursively(Path dir) throws IOException
    {
        if (dir == null || !Files.exists(dir))
        {
            return;
        }
        try (java.util.stream.Stream<Path> walk = Files.walk(dir))
        {
            // Delete children before parents (reverse of the walk order).
            List<Path> paths = new ArrayList<>();
            walk.forEach(paths::add);
            for (int i = paths.size() - 1; i >= 0; i--)
            {
                Files.deleteIfExists(paths.get(i));
            }
        }
    }

    @Test
    public void testBundlesInfoLineFormat()
    {
        assertEquals(
            "com.ozon.edt.mcp.server,1.0.0.202609052012,/p/plugins/com.ozon.edt.mcp.server_1.0.0.202609052012.jar,4,false", //$NON-NLS-1$
            PluginSelfUpdate.bundlesInfoLine("com.ozon.edt.mcp.server", //$NON-NLS-1$
                "1.0.0.202609052012", "/p/plugins/com.ozon.edt.mcp.server_1.0.0.202609052012.jar")); //$NON-NLS-1$
    }

    @Test
    public void testParseVersion()
    {
        assertArrayEquals(new long[] {1, 0, 0, 0}, PluginSelfUpdate.parse("1.0.0")); //$NON-NLS-1$
        assertArrayEquals(new long[] {1, 2, 3, 4}, PluginSelfUpdate.parse("1.2.3.4")); //$NON-NLS-1$
        assertArrayEquals(new long[] {1, 0, 0, 0}, PluginSelfUpdate.parse("1")); //$NON-NLS-1$
        assertArrayEquals(new long[] {0, 0, 0, 0}, PluginSelfUpdate.parse(null));
        assertArrayEquals(new long[] {0, 0, 0, 0}, PluginSelfUpdate.parse("abc")); //$NON-NLS-1$
        // The Tycho build qualifier is a ~2e11 timestamp — must survive as a long,
        // not collapse to 0 via int overflow (else every build compares equal).
        assertArrayEquals(new long[] {1, 0, 0, 202609052012L},
            PluginSelfUpdate.parse("1.0.0.202609052012")); //$NON-NLS-1$
    }

    @Test
    public void testCompareVersions()
    {
        assertTrue(PluginSelfUpdate.compare(
            PluginSelfUpdate.parse("1.0.0.2"), PluginSelfUpdate.parse("1.0.0.1")) > 0); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(PluginSelfUpdate.compare(
            PluginSelfUpdate.parse("1.0.1"), PluginSelfUpdate.parse("1.0.0.999")) > 0); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(PluginSelfUpdate.compare(
            PluginSelfUpdate.parse("1.0.0.1"), PluginSelfUpdate.parse("1.0.0.1")) == 0); //$NON-NLS-1$
        assertTrue(PluginSelfUpdate.compare(
            PluginSelfUpdate.parse("2.0.0"), PluginSelfUpdate.parse("1.9.9")) > 0); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testIsNewer()
    {
        assertTrue(PluginSelfUpdate.isNewer("1.0.0.5", "1.0.0.4")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(PluginSelfUpdate.isNewer("1.0.0.4", "1.0.0.5")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(PluginSelfUpdate.isNewer("1.0.0.4", "1.0.0.4")); //$NON-NLS-1$
        assertFalse(PluginSelfUpdate.isNewer(null, "1.0.0")); //$NON-NLS-1$
        assertFalse(PluginSelfUpdate.isNewer("1.0.0", null));
    }

    @Test
    public void testVersionOfJar()
    {
        assertEquals("1.0.0.202609052012", //$NON-NLS-1$
            PluginSelfUpdate.versionOfJar("com.ozon.edt.mcp.server_1.0.0.202609052012.jar")); //$NON-NLS-1$
        assertNull(PluginSelfUpdate.versionOfJar("some.other_1.0.0.jar")); //$NON-NLS-1$
        assertNull(PluginSelfUpdate.versionOfJar("com.ozon.edt.mcp.server_1.0.0")); //$NON-NLS-1$
        assertNull(PluginSelfUpdate.versionOfJar(null));
    }

    @Test
    public void testHighestBundleJarPicksTheNewest() throws IOException
    {
        Path plugins = Files.createDirectory(tmp.resolve("plugins")); //$NON-NLS-1$
        Files.write(plugins.resolve("com.ozon.edt.mcp.server_1.0.0.202609050900.jar"), //$NON-NLS-1$
            new byte[0]);
        Files.write(plugins.resolve("com.ozon.edt.mcp.server_1.0.0.202609052012.jar"), //$NON-NLS-1$
            new byte[0]);
        Files.write(plugins.resolve("other_1.0.0.jar"), new byte[0]); //$NON-NLS-1$

        assertEquals("com.ozon.edt.mcp.server_1.0.0.202609052012.jar", //$NON-NLS-1$
            PluginSelfUpdate.highestBundleJar(plugins));
    }

    @Test
    public void testHighestBundleJarOnMissingDirIsNull()
    {
        assertNull(PluginSelfUpdate.highestBundleJar(tmp.resolve("nope"))); //$NON-NLS-1$
    }

    @Test
    public void testRewriteBundlesInfoReplacesOnlyOurLine() throws IOException
    {
        Path file = tmp.resolve("bundles.info"); //$NON-NLS-1$
        String oldPath = "/old/plugins/com.ozon.edt.mcp.server_1.0.0.1.jar"; //$NON-NLS-1$
        Files.write(file, java.util.Arrays.asList(
            "a,1.0.0,x,4,false", //$NON-NLS-1$
            "com.ozon.edt.mcp.server,1.0.0.1," + oldPath + ",4,false", //$NON-NLS-1$
            "b,2.0.0,y,4,false" //$NON-NLS-1$
        ), StandardCharsets.UTF_8);

        int replaced = PluginSelfUpdate.rewriteBundlesInfo(file,
            "com.ozon.edt.mcp.server", "1.0.0.2", "/new/plugins/com.ozon.edt.mcp.server_1.0.0.2.jar"); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals(1, replaced);
        String content = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        assertTrue(content.contains("com.ozon.edt.mcp.server,1.0.0.2,/new/plugins/com.ozon.edt.mcp.server_1.0.0.2.jar,4,false")); //$NON-NLS-1$
        assertFalse(content.contains(oldPath));
        assertTrue(content.contains("a,1.0.0,x,4,false")); //$NON-NLS-1$
        assertTrue(content.contains("b,2.0.0,y,4,false")); //$NON-NLS-1$
    }

    @Test
    public void testRewriteBundlesInfoMissingBundleReturnsZero() throws IOException
    {
        Path file = tmp.resolve("bundles.info"); //$NON-NLS-1$
        Files.write(file, java.util.Arrays.asList("a,1.0.0,x,4,false"), StandardCharsets.UTF_8); //$NON-NLS-1$
        int replaced = PluginSelfUpdate.rewriteBundlesInfo(file,
            "com.ozon.edt.mcp.server", "1.0.0.2", "/new.jar"); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(0, replaced);
    }

    @Test
    public void testInstallJarIntoPoolCopiesAndCleansStale() throws IOException
    {
        Path src = Files.createDirectory(tmp.resolve("plugins")); //$NON-NLS-1$
        String newest = "com.ozon.edt.mcp.server_1.0.0.2.jar"; //$NON-NLS-1$
        Files.write(src.resolve(newest), "new-jar".getBytes(StandardCharsets.UTF_8)); //$NON-NLS-1$

        Path home = Files.createDirectory(tmp.resolve("home")); //$NON-NLS-1$
        // Seed a stale old jar in the pool first.
        Path pool = PluginSelfUpdate.poolPluginsDir(home.toString());
        Files.createDirectories(pool);
        Files.write(pool.resolve("com.ozon.edt.mcp.server_1.0.0.1.jar"), //$NON-NLS-1$
            "old-jar".getBytes(StandardCharsets.UTF_8)); //$NON-NLS-1$
        Files.write(pool.resolve("unrelated_1.0.0.jar"), //$NON-NLS-1$
            "keep".getBytes(StandardCharsets.UTF_8)); //$NON-NLS-1$

        Path installed = PluginSelfUpdate.installJarIntoPool(home.toString(), src, newest);

        assertEquals(pool.resolve(newest).toAbsolutePath(), installed.toAbsolutePath());
        assertEquals("new-jar", new String(Files.readAllBytes(installed), StandardCharsets.UTF_8)); //$NON-NLS-1$
        assertFalse(Files.exists(pool.resolve("com.ozon.edt.mcp.server_1.0.0.1.jar"))); //$NON-NLS-1$
        assertTrue(Files.exists(pool.resolve("unrelated_1.0.0.jar"))); //$NON-NLS-1$
    }

    @Test
    public void testConstantDefaults()
    {
        assertNotNull(PluginSelfUpdate.DEFAULT_REPO);
        assertTrue(PluginSelfUpdate.DEFAULT_REPO.startsWith("https://")); //$NON-NLS-1$
        assertEquals("update-site", PluginSelfUpdate.DEFAULT_BRANCH); //$NON-NLS-1$
        assertEquals("com.ozon.edt.mcp.server", PluginSelfUpdate.SYMBOLIC_NAME); //$NON-NLS-1$
    }
}
