/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.io.IOException;
import java.util.Map;
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.utils.snapshot.ProjectReviewHistory;

public final class ResolveProjectReviewTool implements IMcpTool
{
    public static final String NAME = "resolve_project_review";

    @Override
    public String getName() { return NAME; }

    @Override
    public ResponseType getResponseType() { return ResponseType.JSON; }

    @Override
    public String getDescription()
    {
        return "Resolve the review base, build deltas and final commit for a change. "
            + "Full parameters and examples: call get_tool_guide('" + NAME + "').";
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object().stringProperty("repoId", "Server repository alias.", true)
            .stringProperty("projectName", "Exact EDT project.", true)
            .stringProperty("branch", "Full authorized local branch ref.", true)
            .stringProperty("changeId", "Exact change ID.", true)
            .stringProperty("expectedFinal", "Expected full build commit SHA.").build();
    }

    @Override
    public String getOutputSchema()
    {
        return JsonSchemaBuilder.object().booleanProperty("success", "Whether the query succeeded.", true)
            .booleanProperty("complete", "Full and unambiguous ancestry, up to " + ProjectReviewHistory.MAX_COMMITS + " commits.")
            .stringProperty("reason", "Incomplete history reason.").stringProperty("observedHead", "Branch tip at request start.")
            .stringProperty("baseSha", "Parent of earliest build.").stringProperty("finalSha", "Latest build.")
            .objectProperty("range", "Base to final endpoints.")
            .objectArrayProperty("buildDeltas", "Chronological build parent and commit pairs.")
            .objectArrayProperty("phases", "Reachable matching phases in child-before-parent order.")
            .stringProperty("repoId", "Repository alias.").stringProperty("projectName", "EDT project.")
            .stringProperty("branch", "Observed branch.").stringProperty("changeId", "Selected change.").build();
    }

    @Override
    public String execute(Map<String, String> params)
    {
        String error = JsonUtils.requireArguments(params, "repoId", "projectName", "branch", "changeId");
        if (error != null)
        {
            return error;
        }
        try
        {
            return ProjectReviewHistory.resolve(JsonUtils.extractStringArgument(params, "repoId"),
                JsonUtils.extractStringArgument(params, "projectName"), JsonUtils.extractStringArgument(params, "branch"),
                JsonUtils.extractStringArgument(params, "changeId"), JsonUtils.extractStringArgument(params, "expectedFinal")).toJson();
        }
        catch (IOException e)
        {
            return ToolResult.error(e.getMessage()).toJson();
        }
        catch (RuntimeException | LinkageError e)
        {
            return ToolResult.error("Review history is unavailable.").toJson();
        }
    }
}
