/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 *
 * edt-mcp-vanessa: project configuration for Vanessa Automation BDD runs.
 */

package com.ditrix.edt.mcp.server.tools.impl.vanessa;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IPath;

import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.utils.ProjectContext;

/**
 * The runtime parameters needed to launch a Vanessa Automation BDD run on a
 * project, read from the project's {@code env.sh} (the same source
 * {@code run-edt.sh} uses).
 *
 * <p><b>Primary (provisioned) layout:</b> {@code &lt;EDT-project-root&gt;/.vanessa/env.sh} -
 * created on demand for the project by {@code vanessa_setup} ({@code VanessaBootstrap}) so the
 * VA environment travels with the project. <b>Legacy fallback:</b>
 * {@code ~/.1c-tools/vanessa/projects/&lt;project&gt;/env.sh} is still honoured for
 * previously-configured projects. The values (infobase, binary, VAParams, launch config,
 * VA_Runner) are the operator-edited source of truth. This class parses the simple
 * {@code KEY="value"} lines and expands {@code $HOME} (and already-parsed variables
 * referenced with {@code $X}).</p>
 *
 * <p>The candidate-priority logic is pure ({@link #resolveEnvCandidates}) so the ordering
 * and fallback are unit-testable without an EDT runtime; the workspace lookup for the project
 * root is isolated in {@link #projectRoot} and degrades gracefully to the legacy layout.</p>
 */
public final class VanessaProjectConfig
{
    /** Prefix of the out-of-git config directory the operator keeps per project (legacy). */
    private static final String VANESSA_WS = ".1c-tools/vanessa"; //$NON-NLS-1$

    /** Per-project VA directory the plugin provisions in the EDT project root (primary). */
    static final String VANESSA_DIR = ".vanessa"; //$NON-NLS-1$

    private static final String KEY_PROJ = "PROJ"; //$NON-NLS-1$
    private static final String KEY_IB_BASE = "IB_BASE"; //$NON-NLS-1$
    private static final String KEY_DB_USER = "DB_USER"; //$NON-NLS-1$
    private static final String KEY_DB_PWD = "DB_PWD"; //$NON-NLS-1$
    private static final String KEY_VAPARAMS = "VAPARAMS"; //$NON-NLS-1$
    private static final String KEY_EDT_LAUNCH = "EDT_LAUNCH"; //$NON-NLS-1$
    private static final String KEY_EDT_PROJ = "EDT_PROJ"; //$NON-NLS-1$
    private static final String KEY_EDT_OBJECT = "EDT_OBJECT"; //$NON-NLS-1$
    private static final String KEY_BIN1C = "BIN1C"; //$NON-NLS-1$
    private static final String KEY_FEATURES_DIR = "FEATURES_DIR"; //$NON-NLS-1$
    private static final String KEY_VA_LIBS = "VA_LIBS"; //$NON-NLS-1$

    /** Default location of the Vanessa-Automation executable data processor (epf). */
    private static final String DEFAULT_EPF =
        "$HOME/Downloads/vanessa-automation/vanessa-automation.epf"; //$NON-NLS-1$

    /** Project key (e.g. {@code afm}). */
    public final String project;
    /** File infobase path (may be trailing-empty for not-yet-provisioned projects). */
    public final String ibBase;
    /** Database user, or empty when authentication is not required. */
    public final String dbUser;
    /** Database password, or empty. */
    public final String dbPwd;
    /** Absolute path to the base VAParams.json to derive run overrides from. */
    public final String vaparams;
    /** Name of the EDT runtime-client launch configuration that starts the client. */
    public final String edtLaunch;
    /** External-object project name (VA_Runner) that drives the run. */
    public final String edtProj;
    /** External-object fully-qualified name, e.g. {@code ExternalDataProcessor.VA_Runner}. */
    public final String edtObject;
    /** Path to the 1cv8c binary. */
    public final String bin1c;
    /** Default feature directory for the project. */
    public final String featuresDir;
    /** Optional absolute library directory (VA_LIBS), or empty. */
    public final String vaLibs;
    /** Absolute path to the Vanessa epf module. */
    public final String epf;

    private VanessaProjectConfig(String project, Map<String, String> raw)
    {
        this.project = project;
        this.ibBase = raw.getOrDefault(KEY_IB_BASE, ""); //$NON-NLS-1$
        this.dbUser = raw.getOrDefault(KEY_DB_USER, ""); //$NON-NLS-1$
        this.dbPwd = raw.getOrDefault(KEY_DB_PWD, ""); //$NON-NLS-1$
        this.vaparams = raw.getOrDefault(KEY_VAPARAMS, ""); //$NON-NLS-1$
        this.edtLaunch = raw.getOrDefault(KEY_EDT_LAUNCH, ""); //$NON-NLS-1$
        this.edtProj = raw.getOrDefault(KEY_EDT_PROJ, "VA_Runner"); //$NON-NLS-1$
        this.edtObject = raw.getOrDefault(KEY_EDT_OBJECT,
            "ExternalDataProcessor.VA_Runner"); //$NON-NLS-1$
        this.bin1c = raw.getOrDefault(KEY_BIN1C, ""); //$NON-NLS-1$
        this.featuresDir = raw.getOrDefault(KEY_FEATURES_DIR, ""); //$NON-NLS-1$
        this.vaLibs = raw.getOrDefault(KEY_VA_LIBS, ""); //$NON-NLS-1$
        this.epf = expandHome(raw.getOrDefault("EPF", DEFAULT_EPF)); //$NON-NLS-1$
    }

    /**
     * Loads the configuration for a project, or {@code null} when the env.sh is
     * absent/unreadable.
     *
     * @param project project key, e.g. {@code afm}
     * @return parsed config, or {@code null} if the project env.sh does not exist
     */
    public static VanessaProjectConfig fromProject(String project)
    {
        if (project == null || project.trim().isEmpty())
        {
            return null;
        }
        Map<String, String> raw = parseEnvFile(envPath(project.trim()));
        if (raw.isEmpty())
        {
            return null;
        }
        return new VanessaProjectConfig(project.trim(), raw);
    }

    /**
     * The default feature target for the project: its env.sh FEATURES_DIR.
     *
     * @return the configured feature directory path
     */
    public String defaultFeatureTarget()
    {
        return expandHome(featuresDir);
    }

    /**
     * The active {@code env.sh} for a project: the first existing candidate in priority
     * order ({@code <projectRoot>/.vanessa/env.sh} preferred, then the legacy
     * {@code ~/.1c-tools/vanessa/projects/<project>/env.sh}). When neither exists, the most
     * preferred candidate path is returned so callers report the location to provide.
     *
     * @param project project key
     * @return the active env.sh path (never {@code null})
     */
    static Path envPath(String project)
    {
        List<Path> candidates = resolveEnvCandidates(
            projectRoot(project), project, System.getProperty("user.home")); //$NON-NLS-1$
        for (Path candidate : candidates)
        {
            if (candidate != null && Files.isRegularFile(candidate))
            {
                return candidate;
            }
        }
        return candidates.isEmpty() ? null : candidates.get(0);
    }

    /**
     * Ordered {@code env.sh} candidates for a project, most-preferred first. Pure: no
     * filesystem access, no workspace - the caller picks which candidate exists.
     *
     * @param projectRoot the on-disk EDT project root, or {@code null}
     * @param project the project key
     * @param userHome the user home directory
     * @return the candidate paths in priority order (never {@code null}, never empty)
     */
    static List<Path> resolveEnvCandidates(Path projectRoot, String project, String userHome)
    {
        List<Path> candidates = new ArrayList<>();
        if (projectRoot != null)
        {
            candidates.add(projectRoot.resolve(VANESSA_DIR).resolve("env.sh")); //$NON-NLS-1$
        }
        if (userHome != null)
        {
            candidates.add(
                Paths.get(userHome, VANESSA_WS, "projects", project, "env.sh")); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return candidates;
    }

    /**
     * On-disk root of the EDT project, or {@code null} when the project does not resolve
     * (e.g. headless context, or a Vanessa project key that is not an EDT project name).
     *
     * @param project project key
     * @return the absolute project root, or {@code null}
     */
    static Path projectRoot(String project)
    {
        try
        {
            IProject p = ProjectContext.of(project).project();
            if (p == null)
            {
                return null;
            }
            IPath location = p.getLocation();
            return location == null ? null : location.toFile().toPath();
        }
        catch (RuntimeException | LinkageError e)
        {
            // No EDT workspace here (pure unit run): callers fall back to the legacy layout.
            return null;
        }
    }

    /**
     * The per-project {@code .vanessa/} directory under an EDT project root - the
     * provisioned home of env.sh / VAParams.json / features / run artifacts.
     *
     * @param projectRoot the on-disk EDT project root (non-null)
     * @return the {@code .vanessa} directory path
     */
    static Path vanessaDir(Path projectRoot)
    {
        return projectRoot.resolve(VANESSA_DIR);
    }

    /**
     * Path to the {@code va/<project>} directory holding generated run artifacts -
     * {@code <projectRoot>/.vanessa/va/<project>} for a resolvable EDT project, else the
     * legacy {@code ~/.1c-tools/vanessa/va/<project>}.
     */
    public Path vaDir()
    {
        Path projectRoot = projectRoot(project);
        if (projectRoot != null)
        {
            return projectRoot.resolve(VANESSA_DIR).resolve("va").resolve(project); //$NON-NLS-1$
        }
        String home = System.getProperty("user.home"); //$NON-NLS-1$
        return Paths.get(home, VANESSA_WS, "va", project); //$NON-NLS-1$
    }

    /**
     * Path to the {@code out/<project>} directory holding logs + reports -
     * {@code <projectRoot>/.vanessa/out/<project>} for a resolvable EDT project, else the
     * legacy {@code ~/.1c-tools/vanessa/out/<project>}.
     */
    public Path outDir()
    {
        Path projectRoot = projectRoot(project);
        if (projectRoot != null)
        {
            return projectRoot.resolve(VANESSA_DIR).resolve("out").resolve(project); //$NON-NLS-1$
        }
        String home = System.getProperty("user.home"); //$NON-NLS-1$
        return Paths.get(home, VANESSA_WS, "out", project); //$NON-NLS-1$
    }

    /**
     * Parses the simple {@code KEY="value"} / {@code KEY=value} format env.sh
     * produced by the operator, expanding {@code $HOME} and earlier variables.
     *
     * @param path the env.sh path
     * @return ordered map of key to expanded value (never null)
     */
    static Map<String, String> parseEnvFile(Path path)
    {
        Map<String, String> out = new LinkedHashMap<>();
        if (path == null || !Files.isRegularFile(path))
        {
            return out;
        }
        try
        {
            for (String line : Files.readAllLines(path, StandardCharsets.UTF_8))
            {
                String t = line.trim();
                if (t.isEmpty() || t.startsWith("#")) //$NON-NLS-1$
                {
                    continue;
                }
                int eq = t.indexOf('=');
                if (eq <= 0)
                {
                    continue;
                }
                String key = t.substring(0, eq).trim();
                String val = t.substring(eq + 1).trim();
                // Strip the trailing inline comment (e.g. "key=value # comment") BEFORE
                // removing the quotes: a quoted value followed by a " # comment" no longer
                // ends in a quote, so the quote check below would fail and leave the quotes
                // in the value ("VA_Runner" -> "Project not found: \"VA_Runner\"").
                int hash = val.indexOf(" #"); //$NON-NLS-1$
                if (hash >= 0)
                {
                    val = val.substring(0, hash).trim();
                }
                if ((val.startsWith("\"") && val.endsWith("\"")) //$NON-NLS-1$ //$NON-NLS-2$
                    || (val.startsWith("'") && val.endsWith("'"))) //$NON-NLS-1$ //$NON-NLS-2$
                {
                    val = val.substring(1, val.length() - 1);
                }
                out.put(key, expand(val, out));
            }
        }
        catch (IOException e)
        {
            // Unreadable env.sh: leave the map empty so callers report it cleanly.
        }
        return out;
    }

    private static String expand(String value, Map<String, String> vars)
    {
        String v = expandHome(value);
        for (Map.Entry<String, String> e : vars.entrySet())
        {
            v = v.replace("$" + e.getKey(), e.getValue()); //$NON-NLS-1$
        }
        return v;
    }

    private static String expandHome(String value)
    {
        if (value == null)
        {
            return ""; //$NON-NLS-1$
        }
        String home = System.getProperty("user.home"); //$NON-NLS-1$
        return value.replace("$HOME", home).replace("${HOME}", home); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Friendly "not found" message for a missing/empty project config.
     *
     * @param project the project requested
     * @return a message naming the file to provide
     */
    public static String notFoundMessage(String project)
    {
        return "No Vanessa project config for '" + project + "'. Provide env.sh at " //$NON-NLS-1$ //$NON-NLS-2$
            + envPath(project).toAbsolutePath() + " (preferred: <project>/.vanessa/env.sh via " //$NON-NLS-1$
            + "vanessa_setup; legacy: ~/.1c-tools/vanessa/projects/<proj>/env.sh)."; //$NON-NLS-1$
    }

    /**
     * Convenience: emits a ToolResult error for the given message and marks error.
     *
     * @param message the message
     * @return serialized error ToolResult json
     */
    static String errorJson(String message)
    {
        return ToolResult.error(message).toJson();
    }
}
