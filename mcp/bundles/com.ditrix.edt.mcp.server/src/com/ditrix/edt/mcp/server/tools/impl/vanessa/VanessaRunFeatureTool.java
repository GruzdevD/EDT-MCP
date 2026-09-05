/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 *
 * edt-mcp-vanessa: launch a Vanessa Automation BDD feature/scenario.
 */

package com.ditrix.edt.mcp.server.tools.impl.vanessa;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.tools.impl.LaunchTool;
import com.ditrix.edt.mcp.server.tools.impl.vanessa.VanessaLaunchRunner.RunHandle;

/**
 * Launches a Vanessa Automation BDD run on a project, mirroring the project's
 * {@code run-edt.sh} driver:
 *
 * <ol>
 *   <li>reads <b>outside git</b> {@code ~/.1c-tools/vanessa/projects/&lt;project&gt;/env.sh}
 *       (infobase, binary, VAParams, launch config, Vanessa external object)
 *       via {@link VanessaProjectConfig};</li>
 *   <li>generates the VAParams override VA consumes via {@link VanessaRunArtifacts};</li>
 *   <li>launches the Vanessa Automation external data processor on the project's EDT
 *       client launch configuration with {@code startupOption}
 *       {@code StartFeaturePlayer;VAParams=<override>}, through the shared
 *       {@link LaunchTool} — so the full EDT launch pipeline (DB pre-update,
 *       existing-session handling, auto-confirmed update modals) is reused rather
 *       than re-implemented;</li>
 *   <li>registers a {@link RunHandle} so {@code vanessa_get_execution_status} can
 *       poll the run's terminal state from VA's {@code BDDStatus.log} /
 *       {@code junit/junit.xml} artifacts, and {@code vanessa_get_test_report} can
 *       parse the junit report.</li>
 * </ol>
 *
 * <p>Launch-key names mirror {@link LaunchTool}'s
 * {@code externalObjectProjectName / externalObjectName / launchConfigurationName /
 * updateBeforeLaunch / restartIfRunning / mode} so the two paths stay interchangeable.
 */
public class VanessaRunFeatureTool implements IMcpTool
{
    public static final String NAME = "vanessa_run_feature"; //$NON-NLS-1$

    private static final String KEY_PROJECT = "project"; //$NON-NLS-1$
    private static final String KEY_FEATURE = "feature"; //$NON-NLS-1$
    private static final String KEY_LAUNCH_CONFIGURATION = "launchConfigurationName"; //$NON-NLS-1$

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Launch a Vanessa Automation BDD run on a project (reads the out-of-repo env.sh at " //$NON-NLS-1$
            + "~/.1c-tools/vanessa/projects/<project>/env.sh, generates the VAParams/junit run artifacts, and " //$NON-NLS-1$
            + "starts the VA_Runner external object via the project's EDT launch configuration). Returns a " //$NON-NLS-1$
            + "launchId for vanessa_get_execution_status / vanessa_get_test_report; poll that status until it " //$NON-NLS-1$
            + "leaves 'running'. Parameters and examples: get_tool_guide('vanessa_run_feature')."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty(KEY_PROJECT,
                "Project key whose env.sh drives the run, e.g. 'afm' (see ~/.1c-tools/vanessa/projects/<project>/env.sh).", //$NON-NLS-1$
                true)
            .stringProperty(KEY_FEATURE,
                "Absolute path to a feature file or directory. Defaults to the project's FEATURES_DIR.") //$NON-NLS-1$
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

        VanessaProjectConfig config = VanessaProjectConfig.fromProject(project.trim());
        if (config == null)
        {
            return ToolResult.error(VanessaProjectConfig.notFoundMessage(project.trim())).toJson(); //$NON-NLS-1$
        }

        String feature = params.get(KEY_FEATURE);
        if (feature == null || feature.trim().isEmpty())
        {
            feature = config.defaultFeatureTarget();
        }

        // Generate the run artifacts VA consumes (the same ones run-edt.sh makes).
        VanessaRunArtifacts.Result artifacts;
        try
        {
            artifacts = VanessaRunArtifacts.generate(config, feature);
        }
        catch (IOException e)
        {
            return ToolResult.error("Failed to generate Vanessa run artifacts: " + e.getMessage()).toJson(); //$NON-NLS-1$
        }

        // Launch VA_Runner through the shared EDT launch pipeline so the existing
        // DB-update / session-handling machinery is reused, not re-implemented.
        String configName = params.get(KEY_LAUNCH_CONFIGURATION);
        if (configName == null || configName.trim().isEmpty())
        {
            configName = config.edtLaunch;
        }

        Map<String, String> launchParams = new LinkedHashMap<>();
        launchParams.put("launchConfigurationName", configName); //$NON-NLS-1$
        launchParams.put("externalObjectProjectName", config.edtProj); //$NON-NLS-1$
        launchParams.put("externalObjectName", config.edtObject); //$NON-NLS-1$
        launchParams.put("startupOption", VanessaRunArtifacts.VA_COMMAND + artifacts.vaparamsOverride); //$NON-NLS-1$
        // VA runs as the 1C form-testing TEST MANAGER (its own runner always starts it with
        // /TESTMANAGER): that session mode is what makes the TestedFormGroup types available to
        // the manager so it can drive a test client. Without it VA dies on the first step.
        launchParams.put("automatedTestingMode", "TESTMANAGER"); //$NON-NLS-1$ //$NON-NLS-2$
        launchParams.put("mode", "run"); //$NON-NLS-1$
        launchParams.put("updateBeforeLaunch", "true"); //$NON-NLS-1$
        launchParams.put("restartIfRunning", "true"); //$NON-NLS-1$

        String launchResponse;
        try
        {
            launchResponse = new LaunchTool().execute(launchParams);
        }
        catch (Exception e)
        {
            return ToolResult.error("Failed to launch Vanessa run: " + e.getMessage()).toJson(); //$NON-NLS-1$
        }

        // Register the handle up front (even when EDT declined the launch) so
        // vanessa_get_execution_status can report the failure back to the client.
        RunHandle handle = VanessaLaunchRunner.INSTANCE.register(
            project.trim(), feature, artifacts.outDir);
        if (isError(launchResponse))
        {
            handle.launchError = errorText(launchResponse);
            return launchResponse;
        }

        return ToolResult.success()
            .put("launchId", handle.id) //$NON-NLS-1$
            .put("project", project.trim()) //$NON-NLS-1$
            .put("feature", feature) //$NON-NLS-1$
            .put("configuration", configName) //$NON-NLS-1$
            .put("status", "launching") //$NON-NLS-1$
            .put("outDir", artifacts.outDir) //$NON-NLS-1$
            .put("junitReportPath", handle.junitReportPath) //$NON-NLS-1$
            .toJson();
    }

    /** True when a {@link LaunchTool} response is an error ({@code success:false}). */
    private static boolean isError(String json)
    {
        try
        {
            JsonElement el = JsonParser.parseString(json);
            if (el != null && el.isJsonObject())
            {
                JsonElement success = el.getAsJsonObject().get("success"); //$NON-NLS-1$
                return success != null && success.isJsonPrimitive() && !success.getAsBoolean();
            }
        }
        catch (RuntimeException e)
        {
            // Not JSON — treat as non-error so the raw text surfaces as the result.
        }
        return false;
    }

    /** Extracts the {@code error} text from a {@link LaunchTool} error response. */
    private static String errorText(String json)
    {
        try
        {
            JsonElement el = JsonParser.parseString(json);
            if (el != null && el.isJsonObject())
            {
                JsonObject obj = el.getAsJsonObject();
                JsonElement error = obj.get("error"); //$NON-NLS-1$
                if (error != null && error.isJsonPrimitive())
                {
                    return error.getAsString();
                }
            }
        }
        catch (RuntimeException e)
        {
            // Fall through to the raw string.
        }
        return json;
    }
}
