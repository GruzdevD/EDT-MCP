/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Finds the {@code 1cv8} platform executable (the fat client that ships DESIGNER) needed to run an
 * offline monopolistic configuration update on a file infobase. On macOS there is no
 * {@code ibcmd}/{@code ring}, but a {@code 1cv8} install does provide DESIGNER — this is what
 * {@code update_database} / {@code launch} use for the external-restructure path (3.0.9).
 *
 * <p>The plugin deliberately does NOT guess a single location: the path varies by edition and
 * install layout. Resolution order:
 * <ol>
 *   <li>the caller's explicit parameter (the {@code externalUpdate1cBinary} parameter);</li>
 *   <li>the {@code EDT_MCP_1CV8} environment variable;</li>
 *   <li>a best-effort scan of common install directories (macOS {@code /Applications/1cv8} and
 *       {@code /opt/1cv8}), first executable named {@code 1cv8}.</li>
 * </ol>
 * When none resolves, the caller gets {@link #notFoundError()} — a clear refusal naming all three
 * sources, never a silent guess.
 *
 * <p>Headless-testable: the probe path list is injectable via {@link #resolve(String, Map, List)}.
 */
public final class OneCBinaryResolver
{
    /** Environment variable consulted between the explicit parameter and the on-disk probe. */
    public static final String ENV_VAR = "EDT_MCP_1CV8"; //$NON-NLS-1$

    private OneCBinaryResolver()
    {
        // Utility class
    }

    /**
     * Resolves the {@code 1cv8} binary, using the default probe locations.
     *
     * @param paramValue the caller's {@code externalUpdate1cBinary} parameter (may be {@code null})
     * @param env the environment map, or {@code null} to read the process environment
     * @return an existing, executable path, or {@code empty}
     */
    public static java.util.Optional<String> resolve(String paramValue, Map<String, String> env)
    {
        return resolve(paramValue, env, defaultProbeDirs());
    }

    /**
     * Resolves the {@code 1cv8} binary against an explicit set of probe directories.
     *
     * @param paramValue the caller's parameter (may be {@code null})
     * @param env the environment map, or {@code null} to read the process environment
     * @param probeDirs directories to scan for a {@code 1cv8} executable; never {@code null}
     * @return an existing, executable path, or {@code empty}
     */
    public static java.util.Optional<String> resolve(String paramValue, Map<String, String> env,
        List<Path> probeDirs)
    {
        String explicit = trimToNull(paramValue);
        if (explicit != null)
        {
            return existing(explicit);
        }
        String fromEnv = env != null ? trimToNull(env.get(ENV_VAR)) : trimToNull(System.getenv(ENV_VAR));
        if (fromEnv != null)
        {
            return existing(fromEnv);
        }
        for (Path dir : probeDirs)
        {
            if (dir == null || !Files.isDirectory(dir))
            {
                continue;
            }
            // A binary sitting directly in the scanned directory.
            Path direct = dir.resolve("1cv8"); //$NON-NLS-1$
            if (isExecutable(direct))
            {
                return java.util.Optional.of(direct.toAbsolutePath().toString());
            }
            // The usual install layout: versioned subdirectory, e.g. .../1cv8/8.3.23.1234/1cv8.
            try (Stream<Path> walk = Files.list(dir))
            {
                List<Path> paths = walk.sorted().toList();
                for (Path sub : paths)
                {
                    if (Files.isDirectory(sub))
                    {
                        Path binary = sub.resolve("1cv8"); //$NON-NLS-1$
                        if (isExecutable(binary))
                        {
                            return java.util.Optional.of(binary.toAbsolutePath().toString());
                        }
                    }
                }
            }
            catch (java.io.IOException e)
            {
                // best-effort probe: a read failure on one dir just skips it
            }
        }
        return java.util.Optional.empty();
    }

    /**
     * The default directories scanned when no explicit path is supplied (macOS install layouts).
     *
     * @return the list, never {@code null}
     */
    static List<Path> defaultProbeDirs()
    {
        List<Path> dirs = new ArrayList<>();
        dirs.add(Path.of("/Applications/1cv8")); //$NON-NLS-1$
        dirs.add(Path.of("/opt/1cv8")); //$NON-NLS-1$
        String home = System.getProperty("user.home"); //$NON-NLS-1$
        if (home != null)
        {
            dirs.add(Path.of(home, "Applications", "1cv8")); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return dirs;
    }

    /**
     * An actionable message naming the three sources, for when nothing resolved.
     *
     * @return the refusal text, never {@code null}
     */
    public static String notFoundError()
    {
        return "No 1cv8 platform binary found. The external standalone-server restructure needs the " //$NON-NLS-1$
            + "1C '1cv8' executable (which ships DESIGNER). Provide it via the externalUpdate1cBinary " //$NON-NLS-1$
            + "parameter, set the EDT_MCP_1CV8 environment variable, or install 1C:Enterprise on this " //$NON-NLS-1$
            + "machine (macOS has no ibcmd/ring, but 1cv8 + DESIGNER works)."; //$NON-NLS-1$
    }

    private static java.util.Optional<String> existing(String path)
    {
        Path p = Path.of(path.trim());
        if (isExecutable(p))
        {
            return java.util.Optional.of(p.toAbsolutePath().normalize().toString());
        }
        return java.util.Optional.empty();
    }

    private static boolean isExecutable(Path p)
    {
        return Files.isRegularFile(p) && Files.isExecutable(p);
    }

    private static String trimToNull(String s)
    {
        if (s == null)
        {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
