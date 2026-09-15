/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.Test;

/**
 * Tests for {@link OneCBinaryResolver}, with a temp file standing in for a real {@code 1cv8}
 * executable so the resolution order (explicit parameter → env → probe) is proven without a
 * platform install.
 */
public class OneCBinaryResolverTest
{
    private static Path fakeBinary() throws Exception
    {
        Path dir = Files.createTempDirectory("1cv8-probe");
        Path bin = dir.resolve("1cv8");
        Files.write(bin, new byte[] { (byte)'#', (byte)'!' });
        bin.toFile().setExecutable(true, false);
        return bin;
    }

    @Test
    public void explicitParameterWinsOverEverything() throws Exception
    {
        Path bin = fakeBinary();
        Map<String, String> env = Map.of(OneCBinaryResolver.ENV_VAR, "/env/path");
        Optional<String> r = OneCBinaryResolver.resolve(bin.toString(), env, Collections.emptyList());
        assertTrue(r.isPresent());
        assertEquals(bin.toAbsolutePath().normalize().toString(), r.get());
    }

    @Test
    public void envFallsThroughWhenNoParameter() throws Exception
    {
        Path bin = fakeBinary();
        Map<String, String> env = Map.of(OneCBinaryResolver.ENV_VAR, bin.toString());
        Optional<String> r = OneCBinaryResolver.resolve(null, env, Collections.emptyList());
        assertTrue(r.isPresent());
        assertEquals(bin.toAbsolutePath().normalize().toString(), r.get());
    }

    @Test
    public void probesDirectoryWhenNeitherParameterNorEnv() throws Exception
    {
        Path bin = fakeBinary();
        Optional<String> r = OneCBinaryResolver.resolve(null, Collections.emptyMap(),
            List.of(bin.getParent()));
        assertTrue(r.isPresent());
        assertEquals(bin.toAbsolutePath().normalize().toString(), r.get());
    }

    @Test
    public void nothingResolvesIsEmptyAndHasActionableError() throws Exception
    {
        Path empty = Files.createTempDirectory("1cv8-empty");
        Optional<String> r = OneCBinaryResolver.resolve(null, Collections.emptyMap(),
            List.of(empty));
        assertFalse(r.isPresent());
        assertTrue(OneCBinaryResolver.notFoundError().contains("externalUpdate1cBinary"));
        assertTrue(OneCBinaryResolver.notFoundError().contains(OneCBinaryResolver.ENV_VAR));
    }

    @Test
    public void nonExecutableFileIsIgnored() throws Exception
    {
        Path dir = Files.createTempDirectory("1cv8-nonexec");
        Path bin = dir.resolve("1cv8");
        Files.write(bin, new byte[] { (byte)'x' }); // not set executable
        Optional<String> r = OneCBinaryResolver.resolve(null, Collections.emptyMap(), List.of(dir));
        assertFalse(r.isPresent());
    }
}
