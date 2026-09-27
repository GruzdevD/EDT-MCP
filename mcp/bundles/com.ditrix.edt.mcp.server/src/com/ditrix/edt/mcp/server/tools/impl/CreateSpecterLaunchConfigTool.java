/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.util.Map;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.ILaunchConfigurationWorkingCopy;
import org.eclipse.debug.core.ILaunchManager;

import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.McpKeys;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.utils.LaunchConfigUtils;

/**
 * Creates a Specter UI-testing launch configuration that references a base EDT
 * runtime-client launch configuration.
 *
 * <p>Specter (the EDT plugin {@code ru.ozon.uitp.e2e}) launches UI tests by cloning
 * a base {@link LaunchConfigUtils#LAUNCH_CONFIG_TYPE_ID RuntimeClient} configuration
 * and stamping the bridge startup option and the automated-testing (TESTMANAGER)
 * mode onto the clone. The Specter launch configuration itself carries only the
 * Specter-specific attributes:
 * <ul>
 *   <li>{@code ru.ozon.uitp.e2e.baseLaunchConfig} — the base RuntimeClient config name;</li>
 *   <li>{@code ru.ozon.uitp.e2e.testingPort} — the TESTMANAGER port (0 disables the mode).</li>
 * </ul>
 *
 * <p>The plugin's {@code SpecterLaunchDelegate} resolves the base configuration at
 * launch time, prepares the single reusable clone "Specter: &lt;base&gt;" (never
 * multiplying configs), and starts it. Run and debug share the same config type,
 * exactly like {@link CreateLaunchConfigTool}.
 */
public class CreateSpecterLaunchConfigTool implements IMcpTool
{
    public static final String NAME = "create_specter_launch_config"; //$NON-NLS-1$

    /** The Specter plugin's launch configuration type id. */
    static final String SPECTER_LAUNCH_CONFIG_TYPE_ID = "ru.ozon.uitp.e2e.launcher.specter"; //$NON-NLS-1$

    /** Attribute: base EDT runtime-client launch configuration name. */
    static final String ATTR_BASE_LAUNCH_CONFIG = "ru.ozon.uitp.e2e.baseLaunchConfig"; //$NON-NLS-1$

    /** Attribute: TESTMANAGER port; 0 = the automated-testing mode is off. */
    static final String ATTR_TESTING_PORT = "ru.ozon.uitp.e2e.testingPort"; //$NON-NLS-1$

    /** Default TESTMANAGER port used by the Specter plugin. */
    static final int DEFAULT_TESTING_PORT = 4811;

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Create a Specter UI-testing launch configuration that references a base EDT "
            + "runtime-client configuration. Parameters and examples: " //$NON-NLS-1$
            + "get_tool_guide('create_specter_launch_config')."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty("baseLaunchConfig", //$NON-NLS-1$
                "Base EDT runtime-client launch configuration name (required). "
                + "Use list_configurations to see available configs; the base must be "
                + "a RuntimeClient (1C client) configuration.", true)
            .stringProperty("name", //$NON-NLS-1$
                "Specter config name; default 'Specter UI-тесты' (uniquified). "
                + "If a config with this name already exists the call is rejected.")
            .integerProperty("testingPort", //$NON-NLS-1$
                "TESTMANAGER port for the Specter channel-B testing mode; default 4811. "
                + "Pass 0 to disable the automated-testing mode.")
            .build();
    }

    @Override
    public String getOutputSchema()
    {
        return JsonSchemaBuilder.object()
            .booleanProperty("success", "Whether the operation succeeded", true) //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("action", "Always 'created' on success.") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("name", "Exact name of the created Specter launch configuration.") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("baseLaunchConfig", "Base runtime-client configuration name.") //$NON-NLS-1$ //$NON-NLS-2$
            .integerProperty("testingPort", "TESTMANAGER port stored in the config.") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("type", "Launch configuration type id.") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("message", "Human-readable status message.") //$NON-NLS-1$ //$NON-NLS-2$
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
        // ── 1. Required argument ───────────────────────────────────────────────
        String err = JsonUtils.requireArgument(params, "baseLaunchConfig"); //$NON-NLS-1$
        if (err != null)
        {
            return err;
        }
        String baseName = JsonUtils.extractStringArgument(params, "baseLaunchConfig").trim(); //$NON-NLS-1$
        String nameParam = JsonUtils.extractStringArgument(params, "name"); //$NON-NLS-1$
        int testingPort = JsonUtils.extractIntArgument(params, "testingPort", DEFAULT_TESTING_PORT); //$NON-NLS-1$
        if (testingPort < 0 || testingPort > 65535)
        {
            return ToolResult.error("Invalid testingPort: " + testingPort //$NON-NLS-1$
                + ". Must be 0..65535 (0 disables the TESTMANAGER mode).").toJson(); //$NON-NLS-1$
        }

        // ── 2. Launch manager + Specter type ───────────────────────────────────
        ILaunchManager lm = LaunchConfigUtils.getLaunchManager();
        if (lm == null)
        {
            return ToolResult.error("Eclipse launch manager is not available. " //$NON-NLS-1$
                + "The debug plugin may not have started yet — retry in a moment.").toJson(); //$NON-NLS-1$
        }
        org.eclipse.debug.core.ILaunchConfigurationType specterType =
            lm.getLaunchConfigurationType(SPECTER_LAUNCH_CONFIG_TYPE_ID);
        if (specterType == null)
        {
            return ToolResult.error("Launch configuration type '" + SPECTER_LAUNCH_CONFIG_TYPE_ID //$NON-NLS-1$
                + "' is not registered. The Specter EDT plugin (ru.ozon.uitp.e2e) is not "
                + "installed in this EDT — install it first (see the plugin's README).").toJson(); //$NON-NLS-1$
        }

        // ── 3. Validate the base configuration ─────────────────────────────────
        ILaunchConfiguration base = LaunchConfigUtils.findLaunchConfigByName(lm, baseName);
        if (base == null)
        {
            return ToolResult.error("Base launch configuration not found: '" + baseName //$NON-NLS-1$
                + "'. Use list_configurations to see available configurations.").toJson(); //$NON-NLS-1$
        }
        String baseType = LaunchConfigUtils.getConfigTypeId(base);
        if (!LaunchConfigUtils.LAUNCH_CONFIG_TYPE_ID.equals(baseType))
        {
            return ToolResult.error("Base configuration '" + baseName + "' has type '" + baseType //$NON-NLS-1$ //$NON-NLS-2$
                + "'; Specter requires a runtime-client (" + LaunchConfigUtils.LAUNCH_CONFIG_TYPE_ID //$NON-NLS-1$
                + ") configuration as its base.").toJson(); //$NON-NLS-1$
        }

        // ── 4. Resolve the effective name ───────────────────────────────────────
        String effectiveName = (nameParam != null && !nameParam.isEmpty())
            ? nameParam
            : lm.generateLaunchConfigurationName("Specter UI-тесты"); //$NON-NLS-1$
        ILaunchConfiguration existing = LaunchConfigUtils.findLaunchConfigByName(lm, effectiveName);
        if (existing != null)
        {
            return ToolResult.error("A launch configuration named '" + effectiveName //$NON-NLS-1$
                + "' already exists. Use list_configurations to see existing configs, "
                + "or omit 'name' to auto-generate a unique name.").toJson(); //$NON-NLS-1$
        }

        // ── 5. Create and save ──────────────────────────────────────────────────
        try
        {
            Activator.logInfo(NAME + ": name=" + effectiveName + ", base=" + baseName //$NON-NLS-1$ //$NON-NLS-2$
                + ", testingPort=" + testingPort); //$NON-NLS-1$

            ILaunchConfigurationWorkingCopy wc = specterType.newInstance(null, effectiveName);
            wc.setAttribute(ATTR_BASE_LAUNCH_CONFIG, baseName);
            wc.setAttribute(ATTR_TESTING_PORT, testingPort);
            // Map to the base config's project resource (cosmetic; Run Configurations UI).
            wc.setMappedResources(base.getMappedResources());

            ILaunchConfiguration saved = wc.doSave();

            return ToolResult.success()
                .put("action", "created") //$NON-NLS-1$ //$NON-NLS-2$
                .put("name", saved.getName()) //$NON-NLS-1$
                .put("baseLaunchConfig", baseName) //$NON-NLS-1$
                .put("testingPort", testingPort) //$NON-NLS-1$
                .put("type", SPECTER_LAUNCH_CONFIG_TYPE_ID) //$NON-NLS-1$
                .put("message", "Created Specter launch configuration '" + saved.getName() //$NON-NLS-1$ //$NON-NLS-2$
                    + "' (base '" + baseName + "', TESTMANAGER port " + testingPort + "). "
                    + "Launch it via the Specter plugin UI, or launch the base configuration "
                    + "for an ordinary client session.") //$NON-NLS-1$
                .toJson();
        }
        catch (CoreException e)
        {
            Activator.logError("Error creating Specter launch config: " + effectiveName, e); //$NON-NLS-1$
            return ToolResult.error("Failed to create Specter launch configuration '" + effectiveName //$NON-NLS-1$
                + "': " + e.getMessage()).toJson(); //$NON-NLS-1$
        }
    }
}
