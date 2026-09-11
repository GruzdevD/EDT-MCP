/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 *
 * edt-mcp-vanessa: deploy the newest published plugin build over git.
 */

package com.ditrix.edt.mcp.server.tools.impl.selfupdate;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.IMcpTool;

/**
 * Applies the newest published build of this plugin. Fetches the {@code update-site}
 * branch from git (exactly as {@code plugin_check_for_update} does), then performs
 * the deploy the manual install used to: copies the new
 * {@code com.ditrix.edt.mcp.server_<version>.jar} into {@code ~/.p2/pool/plugins/}
 * (removing the stale one) and rewrites the bundle's {@code bundles.info} line to
 * the new version and path. Takes effect only after an EDT restart.
 */
public class PluginUpdateTool implements IMcpTool
{
    public static final String NAME = "plugin_update"; //$NON-NLS-1$

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Deploy the newest published build of this plugin over git: fetches the update-site branch, " //$NON-NLS-1$
            + "installs the new com.ditrix.edt.mcp.server_<version>.jar into ~/.p2/pool/plugins/ and rewrites " //$NON-NLS-1$
            + "bundles.info. Returns the new version and the installed jar path; the change takes effect after " //$NON-NLS-1$
            + "an EDT restart. Run plugin_check_for_update first to confirm an update is available. " //$NON-NLS-1$
            + "Parameters and examples: get_tool_guide('plugin_update')."; //$NON-NLS-1$
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
    public String getOutputSchema()
    {
        return JsonSchemaBuilder.object()
            .booleanProperty("success", "Whether the call succeeded", true) //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("installedVersion", "Version installed before the update") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("newVersion", "Version that was installed") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("jar", "Updated jar file name") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("target", "Absolute path the jar was written to") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("bundlesInfo", "Note about the bundles.info registration") //$NON-NLS-1$ //$NON-NLS-2$
            .booleanProperty("restartRequired", "Whether EDT must restart to apply the build") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("message", "Human-readable outcome") //$NON-NLS-1$ //$NON-NLS-2$
            .build();
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
            return ToolResult.error("plugin_update: cannot fetch update source: " + e.getMessage()).toJson(); //$NON-NLS-1$
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
        if (installed != null && !PluginSelfUpdate.isNewer(available, installed))
        {
            return ToolResult.error("Already at the newest published build (" + installed //$NON-NLS-1$
                + "); nothing to update.").toJson(); //$NON-NLS-1$
        }

        Path installedJar;
        try
        {
            installedJar = PluginSelfUpdate.installJarIntoPool(home, clone.resolve("plugins"), jar); //$NON-NLS-1$
        }
        catch (IOException e)
        {
            return ToolResult.error("plugin_update: failed to install the jar into the p2 pool: " //$NON-NLS-1$
                + e.getMessage()).toJson(); //$NON-NLS-1$
        }

        String bundlesInfoNote;
        Path bundlesInfo = PluginSelfUpdate.runtimeBundlesInfo();
        if (bundlesInfo == null)
        {
            bundlesInfoNote = "WARNING: running EDT's bundles.info was not resolvable - register the jar manually."; //$NON-NLS-1$
        }
        else
        {
            int replaced = 0;
            try
            {
                replaced = PluginSelfUpdate.rewriteBundlesInfo(bundlesInfo,
                    PluginSelfUpdate.SYMBOLIC_NAME, available, installedJar.toAbsolutePath().toString());
            }
            catch (IOException e)
            {
                bundlesInfoNote = "WARNING: could not write " + bundlesInfo + ": " + e.getMessage(); //$NON-NLS-1$
                replaced = 0;
            }
            bundlesInfoNote = replaced == 1
                ? bundlesInfo.toAbsolutePath().toString()
                : "WARNING: no " + PluginSelfUpdate.SYMBOLIC_NAME //$NON-NLS-1$
                    + " line in " + bundlesInfo.toAbsolutePath() + " - add it manually."; //$NON-NLS-1$
        }

        return ToolResult.success()
            .put("installedVersion", installed == null ? "unknown" : installed) //$NON-NLS-1$ //$NON-NLS-2$
            .put("newVersion", available) //$NON-NLS-1$
            .put("jar", installedJar.getFileName().toString()) //$NON-NLS-1$
            .put("target", installedJar.toAbsolutePath().toString()) //$NON-NLS-1$
            .put("bundlesInfo", bundlesInfoNote) //$NON-NLS-1$
            .put("restartRequired", true) //$NON-NLS-1$
            .put("message", "Plugin updated. Restart EDT for the new build to take effect.") //$NON-NLS-1$
            .toJson();
    }

    private static String valueOrDefault(String value, String fallback)
    {
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }
}
