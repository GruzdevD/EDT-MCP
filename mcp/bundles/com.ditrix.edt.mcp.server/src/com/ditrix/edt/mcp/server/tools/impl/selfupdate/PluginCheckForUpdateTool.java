/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 *
 * edt-mcp-vanessa: check whether a newer plugin build is published on git.
 */

package com.ditrix.edt.mcp.server.tools.impl.selfupdate;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.IMcpTool;

/**
 * Fetches the plugin's artifact branch from its git repo (an {@code update-site}
 * branch holding the built {@code com.ditrix.edt.mcp.server_<version>.jar}) and
 * reports whether a build newer than the one currently running in EDT is
 * available. Auth is the project's git credential config — the same transport
 * the repo is pushed with — so the private repo stays private.
 */
public class PluginCheckForUpdateTool implements IMcpTool
{
    public static final String NAME = "plugin_check_for_update"; //$NON-NLS-1$

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Check whether a newer build of this plugin is published on its git repo: fetches the " //$NON-NLS-1$
            + "update-site branch (which holds the built com.ditrix.edt.mcp.server_<version>.jar) and compares " //$NON-NLS-1$
            + "it with the version running in EDT. Returns installedVersion, availableVersion and " //$NON-NLS-1$
            + "updateAvailable. Authenticates with the project git credentials, so the private repo stays " //$NON-NLS-1$
            + "private. When updateAvailable is true, run plugin_update to apply. " //$NON-NLS-1$
            + "Parameters and examples: get_tool_guide('plugin_check_for_update')."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty(PluginSelfUpdate.KEY_REPO,
                "Git remote to fetch the artifact branch from. Defaults to the project repo.") //$NON-NLS-1$
            .stringProperty(PluginSelfUpdate.KEY_BRANCH,
                "Branch holding the built plugin jar. Defaults to 'update-site'.") //$NON-NLS-1$
            .build();
    }

    @Override
    public ResponseType getResponseType()
    {
        return ResponseType.JSON;
    }

    @Override
    public String execute(Map<String, String> params)
    {
        String home = System.getProperty("user.home"); //$NON-NLS-1$
        String repo = valueOrDefault(params.get(PluginSelfUpdate.KEY_REPO), PluginSelfUpdate.DEFAULT_REPO);
        String branch = valueOrDefault(params.get(PluginSelfUpdate.KEY_BRANCH), PluginSelfUpdate.DEFAULT_BRANCH);

        Path clone;
        try
        {
            clone = PluginSelfUpdate.ensureClone(home, repo, branch);
        }
        catch (IOException e)
        {
            return ToolResult.error("plugin_check_for_update: cannot fetch update source: " + e.getMessage()).toJson(); //$NON-NLS-1$
        }

        String jar = PluginSelfUpdate.highestBundleJar(clone.resolve("plugins")); //$NON-NLS-1$
        if (jar == null)
        {
            return ToolResult.error("No plugin build found in branch '" + branch //$NON-NLS-1$
                + "' (expected plugins/" + PluginSelfUpdate.JAR_PREFIX //$NON-NLS-1$
                + "<version>.jar). Build the plugin and commit the jar to the " + branch + " branch first.").toJson(); //$NON-NLS-1$
        }

        String available = PluginSelfUpdate.versionOfJar(jar);
        String installed = PluginSelfUpdate.installedBundleVersion();

        ToolResult result = ToolResult.success()
            .put("source", repo) //$NON-NLS-1$
            .put("branch", branch) //$NON-NLS-1$
            .put("availableVersion", available) //$NON-NLS-1$
            .put("jar", jar); //$NON-NLS-1$
        if (installed == null)
        {
            // Could not read the running bundle version (e.g. headless), so we cannot
            // rule an update out — report available so the caller can decide.
            result.put("installedVersion", "unknown"); //$NON-NLS-1$
            result.put("updateAvailable", true); //$NON-NLS-1$
        }
        else
        {
            boolean newer = PluginSelfUpdate.isNewer(available, installed);
            result.put("installedVersion", installed); //$NON-NLS-1$
            result.put("updateAvailable", newer); //$NON-NLS-1$
            if (!newer)
            {
                result.put("message", "Already at the newest published build."); //$NON-NLS-1$
            }
            else
            {
                result.put("message", "A newer build is available — run plugin_update to apply, then restart EDT."); //$NON-NLS-1$
            }
        }
        return result.toJson();
    }

    private static String valueOrDefault(String value, String fallback)
    {
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }
}
