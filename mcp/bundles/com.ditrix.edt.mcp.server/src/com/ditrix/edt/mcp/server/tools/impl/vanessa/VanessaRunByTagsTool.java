/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 *
 * edt-mcp-vanessa: launch every Vanessa Automation scenario carrying a tag.
 */

package com.ditrix.edt.mcp.server.tools.impl.vanessa;

import java.util.Map;

import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.IMcpTool;

/**
 * Thin wrapper over {@link VanessaRunFeatureTool#launch} that runs only the
 * scenarios/features of a project carrying a given Vanessa tag. It reuses the
 * same launch pipeline (artifacts generation + EDT launch + handle
 * registration), only pre-filling the {@code СписокТеговОтбор} VAParams override
 * from its {@code tags} parameter. Returns a launchId consumable by the same
 * status/report tools as {@code vanessa_run_feature}.
 */
public class VanessaRunByTagsTool implements IMcpTool
{
    public static final String NAME = "vanessa_run_by_tags"; //$NON-NLS-1$

    private static final String KEY_PROJECT = "project"; //$NON-NLS-1$
    private static final String KEY_TAGS = "tags"; //$NON-NLS-1$
    private static final String KEY_FEATURE_DIR = "featureDir"; //$NON-NLS-1$
    private static final String KEY_LAUNCH_CONFIGURATION = "launchConfigurationName"; //$NON-NLS-1$

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Launch a Vanessa Automation BDD run limited to scenarios/features carrying a given tag " //$NON-NLS-1$
            + "(sets the VAParams tag filter, generates the run artifacts, and starts VA_Runner on the " //$NON-NLS-1$
            + "project's EDT launch configuration). Returns a launchId for vanessa_get_execution_status / " //$NON-NLS-1$
            + "vanessa_get_test_report, like vanessa_run_feature. Parameters and examples: " //$NON-NLS-1$
            + "get_tool_guide('vanessa_run_by_tags')."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty(KEY_PROJECT,
                "Project key whose env.sh drives the run, e.g. 'afm' (see ~/.1c-tools/vanessa/projects/<project>/env.sh).", //$NON-NLS-1$
                true)
            .stringProperty(KEY_TAGS,
                "Comma/space separated Vanessa tags to run, e.g. 'smoke,regress' (VAParams СписокТеговОтбор).", //$NON-NLS-1$
                true)
            .stringProperty(KEY_FEATURE_DIR,
                "Feature file or directory to scope the tag search to. Defaults to the project's FEATURES_DIR.") //$NON-NLS-1$
            .stringProperty(KEY_LAUNCH_CONFIGURATION,
                "Exact name of the EDT launch configuration that starts the 1C client. Defaults to the project's " //$NON-NLS-1$
                    + "EDT_LAUNCH from env.sh.") //$NON-NLS-1$
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
        String project = params.get(KEY_PROJECT);
        if (project == null || project.trim().isEmpty())
        {
            return ToolResult.error("project is required.").toJson(); //$NON-NLS-1$
        }
        String tags = params.get(KEY_TAGS);
        if (tags == null || tags.trim().isEmpty())
        {
            return ToolResult.error("tags is required.").toJson(); //$NON-NLS-1$
        }

        VanessaProjectConfig config = VanessaProjectConfig.fromProject(project.trim());
        if (config == null)
        {
            return ToolResult.error(VanessaProjectConfig.notFoundMessage(project.trim())).toJson(); //$NON-NLS-1$
        }

        String feature = params.get(KEY_FEATURE_DIR);
        if (feature == null || feature.trim().isEmpty())
        {
            feature = config.defaultFeatureTarget();
        }
        String configName = params.get(KEY_LAUNCH_CONFIGURATION);
        if (configName == null || configName.trim().isEmpty())
        {
            configName = config.edtLaunch;
        }

        VanessaRunArtifacts.GenerateOptions options = new VanessaRunArtifacts.GenerateOptions();
        options.tagsFilter = tags.trim();

        final VanessaLaunchOutcome out;
        try
        {
            out = VanessaRunFeatureTool.launch(config, feature, options, configName);
        }
        catch (java.io.IOException e)
        {
            return ToolResult.error("Failed to generate Vanessa run artifacts: " + e.getMessage()).toJson(); //$NON-NLS-1$
        }
        if (!out.success)
        {
            return out.rawResponse;
        }

        return ToolResult.success()
            .put("launchId", out.id) //$NON-NLS-1$
            .put("project", out.project) //$NON-NLS-1$
            .put("feature", out.feature) //$NON-NLS-1$
            .put("tags", tags.trim()) //$NON-NLS-1$
            .put("configuration", out.configuration) //$NON-NLS-1$
            .put("status", "launching") //$NON-NLS-1$
            .put("outDir", out.outDir) //$NON-NLS-1$
            .put("junitReportPath", out.junitReportPath) //$NON-NLS-1$
            .toJson();
    }
}
