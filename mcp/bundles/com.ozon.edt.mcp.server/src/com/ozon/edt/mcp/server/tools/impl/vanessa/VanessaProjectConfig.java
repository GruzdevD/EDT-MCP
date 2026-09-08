/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 *
 * edt-mcp-vanessa: project configuration for Vanessa Automation BDD runs.
 */

package com.ozon.edt.mcp.server.tools.impl.vanessa;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;

import com.ozon.edt.mcp.server.protocol.ToolResult;

/**
 * The runtime parameters needed to launch a Vanessa Automation BDD run on a
 * project, read from the project's out-of-repo {@code env.sh} (the same source
 * {@code run-edt.sh} uses).
 *
 * <p>Layout: {@code workspace/.1c-tools/vanessa/projects/&lt;project&gt;/env.sh}
 * under the user home is NOT a git repo; the values there (infobase, binary,
 * VAParams, launch config, VA_Runner) are the operator-edited source of truth
 * for every project. This class parses the simple {@code KEY="value"} lines and
 * expands {@code $HOME} (and already-parsed variables referenced with {@code $X}).
 */
public final class VanessaProjectConfig
{
    /** Prefix of the out-of-git config directory the operator keeps per project. */
    private static final String VANESSA_WS = ".1c-tools/vanessa"; //$NON-NLS-1$

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
     * Path to the out-of-git {@code env.sh} for a project.
     */
    static Path envPath(String project)
    {
        String home = System.getProperty("user.home"); //$NON-NLS-1$
        return Paths.get(home, VANESSA_WS, "projects", project, "env.sh"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Path to the {@code va/<project>} directory holding generated run artifacts.
     */
    public Path vaDir()
    {
        String home = System.getProperty("user.home"); //$NON-NLS-1$
        return Paths.get(home, VANESSA_WS, "va", project); //$NON-NLS-1$
    }

    /**
     * Path to the {@code out/<project>} directory holding logs + reports.
     */
    public Path outDir()
    {
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
        return "No Vanessa project config for '" + project + "'. Expected env.sh at " //$NON-NLS-1$ //$NON-NLS-2$
            + envPath(project).toAbsolutePath() + " (see ~/.1c-tools/vanessa/projects/<proj>/env.sh)."; //$NON-NLS-1$
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
