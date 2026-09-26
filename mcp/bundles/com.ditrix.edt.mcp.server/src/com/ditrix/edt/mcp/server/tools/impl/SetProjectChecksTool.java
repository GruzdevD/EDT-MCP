/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.util.Map;

import org.eclipse.core.resources.IProject;

import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.McpKeys;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.utils.CheckProcessSupport;
import com.ditrix.edt.mcp.server.utils.ProjectContext;

/**
 * Toggles a project's EDT <em>massive check process</em> — the "расширенные проверки и
 * валидации" that run around infobase synchronization/update and are the usual way to
 * make an {@code update_database} slower or even blocked. Disable it before an update,
 * re-enable it after.
 *
 * <p>This is the per-project preference {@code disableMassiveChecks} (node
 * {@code com.e1c.g5.v8.dt.check}), toggled through the {@code ICheckRepository} service
 * — exactly the path EDT's own project property page («Настройки процесса проверок»)
 * uses, so the change persists and takes effect immediately (no restart). It is
 * reversible: {@code disableMassiveChecks=false} (or the EDT default) restores normal
 * checking. A write, but non-destructive (re-enabling is a no-op restore), so it is not
 * gated by the destructive-consent dialog.
 *
 * <p>Scope: only the file/server project-scoped preference is covered. EDT's separate
 * <em>critical data-integrity markers</em> gate in the Deploy Configuration wizard is
 * NOT this preference and is unaffected.
 */
public class SetProjectChecksTool implements IMcpTool
{
    public static final String NAME = "set_project_checks"; //$NON-NLS-1$

    /** Input param: {@code true} disables the massive check process, {@code false} re-enables it. */
    private static final String KEY_DISABLE_MASSIVE_CHECKS = "disableMassiveChecks"; //$NON-NLS-1$

    /** Output key: whether the read-back confirms the flag is currently disabled. */
    private static final String KEY_DISABLED = "disableMassiveChecks"; //$NON-NLS-1$

    /** Output key: whether the call actually changed the flag (false = it already had the value). */
    private static final String KEY_CHANGED = "changed"; //$NON-NLS-1$

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Toggle an EDT project's MASSIVE CHECK PROCESS - the extended checks/validations that " //$NON-NLS-1$
            + "run around infobase sync and make update_database slow (or blocked). Set " //$NON-NLS-1$
            + "disableMassiveChecks=true before update_database and back to false after. Reversible, " //$NON-NLS-1$
            + "per-project, takes effect immediately (no restart). Parameters and examples: " //$NON-NLS-1$
            + "get_tool_guide('set_project_checks')."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty(McpKeys.PROJECT_NAME,
                "EDT project whose massive-check process to toggle (required).", true) //$NON-NLS-1$
            .booleanProperty(KEY_DISABLE_MASSIVE_CHECKS,
                "true = disable the massive check process (run fast); false = re-enable it (restore " //$NON-NLS-1$
                + "full checks). This is EDT's per-project 'disableMassiveChecks' preference. " //$NON-NLS-1$
                + "(required)", true) //$NON-NLS-1$
            .build();
    }

    @Override
    public String getOutputSchema()
    {
        return JsonSchemaBuilder.object()
            .booleanProperty("success", "Whether the operation succeeded", true) //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty(McpKeys.PROJECT, "Target EDT project name.") //$NON-NLS-1$
            .booleanProperty(KEY_DISABLED,
                "Read-back: whether the massive check process is now disabled for the project.") //$NON-NLS-1$
            .booleanProperty(KEY_CHANGED,
                "Whether this call actually changed the flag (false = it already had the value).") //$NON-NLS-1$
            .stringProperty(McpKeys.MESSAGE, "Human-readable status.") //$NON-NLS-1$
            .build();
    }

    @Override
    public ResponseType getResponseType()
    {
        return ResponseType.JSON;
    }

    @Override
    public boolean connectsToInfobase()
    {
        // Pure EDT workspace-metadata preference write — no infobase connection.
        return false;
    }

    @Override
    public String execute(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, McpKeys.PROJECT_NAME);
        if (projectName == null || projectName.isEmpty())
        {
            return ToolResult.error("projectName is required (the EDT project whose massive-check "
                + "process to toggle). Use list_projects to see available projects.").toJson(); //$NON-NLS-1$
        }
        Boolean target = parseDisableMassiveChecks(params);
        if (target == null)
        {
            String value = params.get(KEY_DISABLE_MASSIVE_CHECKS);
            if (value == null || value.isEmpty())
            {
                return ToolResult.error("disableMassiveChecks is required: true (disable the massive "
                    + "check process before an update) or false (re-enable it after).").toJson(); //$NON-NLS-1$
            }
            return ToolResult.error("Invalid disableMassiveChecks value: '" + value //$NON-NLS-1$
                + "'. Expected true or false.").toJson(); //$NON-NLS-1$
        }

        ProjectContext ctx = ProjectContext.of(projectName);
        if (!ctx.exists())
        {
            return ToolResult.error(ProjectContext.notFoundMessage(projectName)).toJson();
        }
        if (!ctx.isOpen())
        {
            return ToolResult.error("Project is closed: " + projectName //$NON-NLS-1$
                + ". Open the project in EDT first.").toJson(); //$NON-NLS-1$
        }
        IProject project = ctx.project();

        Boolean before = CheckProcessSupport.isMassiveCheckProcessDisabled(project);
        String writeError = CheckProcessSupport.setMassiveCheckProcessDisabled(target, project);
        if (writeError != null)
        {
            return ToolResult.error(writeError).toJson();
        }
        Boolean after = CheckProcessSupport.isMassiveCheckProcessDisabled(project);

        boolean changed = before == null || !target.equals(before);
        boolean confirmed = Boolean.TRUE.equals(after) == target;

        ToolResult result = ToolResult.success()
            .put(McpKeys.PROJECT, projectName)
            .put(KEY_DISABLED, Boolean.TRUE.equals(after))
            .put(KEY_CHANGED, changed)
            .put(McpKeys.MESSAGE, message(projectName, target, changed, confirmed));
        return result.toJson();
    }

    /**
     * Parses the {@code disableMassiveChecks} boolean argument into {@code true}/{@code false},
     * or {@code null} when the argument is absent, blank or not a boolean (the caller decides
     * how to word the refusal). Recognises the same value spellings
     * {@link JsonUtils#extractBooleanArgument} accepts ({@code true/1/yes}, {@code false/0/no}).
     * Exposed (package-private) so invalid inputs are unit-testable without a live project.
     *
     * @param params the (possibly {@code null}) params map
     * @return the parsed boolean, or {@code null} when absent/invalid
     */
    static Boolean parseDisableMassiveChecks(Map<String, String> params)
    {
        if (params == null)
        {
            return null;
        }
        String value = params.get(KEY_DISABLE_MASSIVE_CHECKS);
        if (value == null || value.isEmpty())
        {
            return null;
        }
        value = value.trim().toLowerCase();
        if ("true".equals(value) || "1".equals(value) || "yes".equals(value)) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {
            return Boolean.TRUE;
        }
        if ("false".equals(value) || "0".equals(value) || "no".equals(value)) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {
            return Boolean.FALSE;
        }
        return null;
    }

    private static String message(String projectName, boolean target, boolean changed, boolean confirmed)
    {
        String state = target ? "disabled" : "enabled"; //$NON-NLS-1$ //$NON-NLS-2$
        return "Massive check process for project '" + projectName + "' is now " + state //$NON-NLS-1$ //$NON-NLS-2$
            + (confirmed ? "" : " (the read-back could not confirm the new state)") //$NON-NLS-1$
            + (changed ? "." : " (it was already " + state + ")."); //$NON-NLS-1$
    }
}
