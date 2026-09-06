/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 *
 * edt-mcp-vanessa: poll the status of a launched BDD run.
 */

package com.ozon.edt.mcp.server.tools.impl.vanessa;

import java.util.Map;

import com.ozon.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ozon.edt.mcp.server.protocol.ToolResult;
import com.ozon.edt.mcp.server.tools.IMcpTool;
import com.ozon.edt.mcp.server.tools.impl.vanessa.VanessaLaunchRunner.RunHandle;
import com.ozon.edt.mcp.server.tools.impl.vanessa.VanessaLaunchRunner.State;

/**
 * Polls the execution status of a Vanessa BDD run previously started by
 * {@code vanessa_run_feature}. The terminal state is read from the artifacts VA
 * writes into the project out dir — a numeric {@code BDDStatus.log} means the run
 * finished ({@code 0} = passed, otherwise failed) — so this tool answers "is it
 * done yet, and did it pass" the same way the project's {@code run-edt.sh} does.
 */
public class VanessaGetExecutionStatusTool implements IMcpTool
{
    public static final String NAME = "vanessa_get_execution_status"; //$NON-NLS-1$

    private static final String KEY_LAUNCH_ID = "launchId"; //$NON-NLS-1$

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Poll the execution status of a Vanessa Automation BDD run started by vanessa_run_feature. " //$NON-NLS-1$
            + "Reads VA's BDDStatus.log artifact, so a run is 'running' until that file carries a numeric " //$NON-NLS-1$
            + "verdict (0 = passed). Returns running/passed/failed plus the junit path. " //$NON-NLS-1$
            + "Parameters and examples: get_tool_guide('vanessa_get_execution_status')."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .integerProperty(KEY_LAUNCH_ID, "The launchId returned by vanessa_run_feature.", true) //$NON-NLS-1$
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
        Long id = VanessaLaunchRunner.parseLaunchId(params.get(KEY_LAUNCH_ID));
        if (id == null)
        {
            return ToolResult.error("launchId must be a whole number, got: " + params.get(KEY_LAUNCH_ID)).toJson(); //$NON-NLS-1$
        }

        RunHandle handle = VanessaLaunchRunner.INSTANCE.get(id);
        if (handle == null)
        {
            return ToolResult.error("Unknown launchId " + id + ".").toJson(); //$NON-NLS-1$
        }

        State state = handle.state();

        ToolResult result = ToolResult.success()
            .put("launchId", id) //$NON-NLS-1$
            .put("project", handle.project) //$NON-NLS-1$
            .put("feature", handle.feature) //$NON-NLS-1$
            .put("status", state.name().toLowerCase()); //$NON-NLS-1$
        if (state != State.RUNNING)
        {
            result.put("junitReportPath", handle.junitReportPath); //$NON-NLS-1$
        }
        if (state == State.FAILED && handle.launchError != null)
        {
            result.put("error", handle.launchError); //$NON-NLS-1$
        }
        return result.toJson();
    }
}
