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
    private static final String KEY_TAGS_FILTER = "tagsFilter"; //$NON-NLS-1$
    private static final String KEY_TAGS_IGNORE = "tagsIgnore"; //$NON-NLS-1$
    private static final String KEY_SCENARIOS = "scenarios"; //$NON-NLS-1$
    private static final String KEY_RETRIES = "retries"; //$NON-NLS-1$
    private static final String KEY_SCREENSHOTS_ON_ERROR = "screenshotsOnError"; //$NON-NLS-1$
    private static final String KEY_ASYNC_STEPS = "asyncSteps"; //$NON-NLS-1$
    private static final String KEY_ALLURE = "allure"; //$NON-NLS-1$
    private static final String KEY_REPORT_DIR = "reportDir"; //$NON-NLS-1$

    /** LowerCamelCase run_feature parameters that map onto {@link GenerateOptions}. */
    static final String[] OPTION_KEYS = {
        KEY_TAGS_FILTER, KEY_TAGS_IGNORE, KEY_SCENARIOS, KEY_RETRIES, KEY_SCREENSHOTS_ON_ERROR,
        KEY_ASYNC_STEPS, KEY_ALLURE, KEY_REPORT_DIR
    };

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
            .stringProperty(KEY_TAGS_FILTER,
                "Run only scenarios/features carrying these Vanessa tags (comma/space separated), e.g. 'smoke,regress'.") //$NON-NLS-1$
            .stringProperty(KEY_TAGS_IGNORE,
                "Skip scenarios/features carrying these Vanessa tags.") //$NON-NLS-1$
            .stringProperty(KEY_SCENARIOS,
                "Run only these scenario names (comma-separated), e.g. 'Создание справочника'.") //$NON-NLS-1$
            .integerProperty(KEY_RETRIES,
                "Retry count for a failed scenario (>=1; 2 = one retry after a failure).") //$NON-NLS-1$
            .booleanProperty(KEY_SCREENSHOTS_ON_ERROR,
                "Take a screenshot when a scenario step fails.") //$NON-NLS-1$
            .booleanProperty(KEY_ASYNC_STEPS,
                "Run steps asynchronously (slow/async mode).") //$NON-NLS-1$
            .booleanProperty(KEY_ALLURE,
                "Generate an Allure HTML report next to the junit one.") //$NON-NLS-1$
            .stringProperty(KEY_REPORT_DIR,
                "Absolute out dir for logs/junit/allure instead of the project default (~/.1c-tools/vanessa/out/<project>).") //$NON-NLS-1$
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

        // Lazy provisioning: if the VA runtime epf is not installed yet, start its download
        // and tell the caller to retry once it lands (do not run without the runtime).
        String pending = VanessaBootstrap.lazyEpfPending(NAME,
            VanessaProjectConfig.projectRoot(project.trim()), project.trim());
        if (pending != null)
        {
            return ToolResult.error(pending).toJson(); //$NON-NLS-1$
        }

        String feature = params.get(KEY_FEATURE);
        if (feature == null || feature.trim().isEmpty())
        {
            feature = config.defaultFeatureTarget();
        }

        String configName = params.get(KEY_LAUNCH_CONFIGURATION);
        if (configName == null || configName.trim().isEmpty())
        {
            configName = config.edtLaunch;
        }

        final VanessaLaunchOutcome out;
        try
        {
            out = launch(config, feature, buildOptions(params), configName);
        }
        catch (IOException e)
        {
            return ToolResult.error("Failed to generate Vanessa run artifacts: " + e.getMessage()).toJson(); //$NON-NLS-1$
        }
        if (!out.success)
        {
            // rawResponse already carries the LaunchTool {@code success:false} JSON.
            return out.rawResponse;
        }

        return ToolResult.success()
            .put("launchId", out.id) //$NON-NLS-1$
            .put("project", out.project) //$NON-NLS-1$
            .put("feature", out.feature) //$NON-NLS-1$
            .put("configuration", out.configuration) //$NON-NLS-1$
            .put("status", "launching") //$NON-NLS-1$
            .put("outDir", out.outDir) //$NON-NLS-1$
            .put("junitReportPath", out.junitReportPath) //$NON-NLS-1$
            .put("options", appliedOptions(params)) //$NON-NLS-1$
            .toJson();
    }

    /**
     * Launches a Vanessa run for the caller-shaped pieces, shared between this
     * tool and {@code vanessa_run_by_tags} so the pipeline is never duplicated:
     * generates the run artifacts VA consumes, launches VA_Runner through the
     * shared EDT launch pipeline ({@link LaunchTool}) so the existing DB-update /
     * session-handling machinery is reused, and registers a {@link RunHandle} for
     * the status/report tools.
     *
     * @param config project configuration
     * @param feature feature file or directory to run
     * @param options per-run overrides (may be empty)
     * @param configName EDT launch configuration name
     * @return the launch outcome; the handle is registered even on a rejected launch
     * @throws IOException if artifact generation fails
     */
    static VanessaLaunchOutcome launch(VanessaProjectConfig config, String feature,
        VanessaRunArtifacts.GenerateOptions options, String configName) throws IOException
    {
        VanessaRunArtifacts.Result artifacts =
            VanessaRunArtifacts.generate(config, feature, options);

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
            return new VanessaLaunchOutcome(false,
                ToolResult.error("Failed to launch Vanessa run: " + e.getMessage()).toJson(), //$NON-NLS-1$
                0L, config.project, feature, configName, artifacts.outDir, null);
        }

        // Register the handle up front (even when EDT declined the launch) so
        // vanessa_get_execution_status can report the failure back to the client.
        RunHandle handle = VanessaLaunchRunner.INSTANCE.register(
            config.project, feature, artifacts.outDir);
        if (isError(launchResponse))
        {
            handle.launchError = errorText(launchResponse);
            return new VanessaLaunchOutcome(false, launchResponse, 0L,
                config.project, feature, configName, artifacts.outDir, handle.junitReportPath);
        }
        return new VanessaLaunchOutcome(true, launchResponse, handle.id,
            config.project, feature, configName, artifacts.outDir, handle.junitReportPath);
    }

    /** Builds the per-run {@link GenerateOptions} from the tool parameters (null = absent). */
    private static VanessaRunArtifacts.GenerateOptions buildOptions(Map<String, String> params)
    {
        VanessaRunArtifacts.GenerateOptions o = new VanessaRunArtifacts.GenerateOptions();
        o.tagsFilter = param(params, KEY_TAGS_FILTER);
        o.tagsIgnore = param(params, KEY_TAGS_IGNORE);
        o.scenarios = param(params, KEY_SCENARIOS);
        o.retries = integerParam(params, KEY_RETRIES);
        o.screenshotsOnError = boolParam(params, KEY_SCREENSHOTS_ON_ERROR);
        o.asyncSteps = boolParam(params, KEY_ASYNC_STEPS);
        o.allure = boolParam(params, KEY_ALLURE);
        o.reportDir = param(params, KEY_REPORT_DIR);
        return o;
    }

    /** Echoes back only the options that were actually passed, for transparency. */
    private static Map<String, Object> appliedOptions(Map<String, String> params)
    {
        Map<String, Object> applied = new LinkedHashMap<>();
        for (String k : new String[] { KEY_TAGS_FILTER, KEY_TAGS_IGNORE, KEY_SCENARIOS, KEY_REPORT_DIR })
        {
            String v = param(params, k);
            if (v != null)
            {
                applied.put(k, v);
            }
        }
        Integer retries = integerParam(params, KEY_RETRIES);
        if (retries != null)
        {
            applied.put(KEY_RETRIES, retries);
        }
        for (String k : new String[] { KEY_SCREENSHOTS_ON_ERROR, KEY_ASYNC_STEPS, KEY_ALLURE })
        {
            Boolean b = boolParam(params, k);
            if (b != null)
            {
                applied.put(k, b);
            }
        }
        return applied;
    }

    private static String param(Map<String, String> p, String key)
    {
        String v = p.get(key);
        return v != null && !v.trim().isEmpty() ? v.trim() : null;
    }

    private static Integer integerParam(Map<String, String> p, String key)
    {
        String v = param(p, key);
        if (v == null)
        {
            return null;
        }
        try
        {
            return Integer.valueOf(v);
        }
        catch (NumberFormatException e)
        {
            // Malformed numeric input degrades to "absent" — the schema already told
            // the client it must be an integer, so don't fail the run over a typo.
            return null;
        }
    }

    private static Boolean boolParam(Map<String, String> p, String key)
    {
        String v = param(p, key);
        return v == null ? null : Boolean.valueOf(v);
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
