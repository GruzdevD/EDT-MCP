/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;

import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.utils.snapshot.ProjectSnapshotService;

/** Publishes an authorized, previously reviewed project commit. */
public final class ConfirmProjectCommitTool implements IMcpTool
{
    public static final String NAME = "confirm_project_commit";

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Confirm a reviewed project commit on the authorized local branch. Retry the same plan ID after a lost response. "
            + "Full parameters and examples: call get_tool_guide('" + NAME + "').";
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object().stringProperty("planId", "ID returned by preview_project_commit.", true).build();
    }

    @Override
    public String getOutputSchema()
    {
        return JsonSchemaBuilder.object().booleanProperty("success", "Whether the commit and selected index updates are complete.", true)
            .enumProperty("status", "Result of the identified operation.", "committed", "recovery_required")
            .stringProperty("commitSha", "Full SHA of the journaled commit.")
            .stringProperty("branch", "Full local branch ref.")
            .stringProperty("parentSha", "Expected parent commit.")
            .stringProperty("treeId", "Git tree of the reviewed commit.")
            .booleanProperty("mutationOutcomeUnknown", "Whether the journal requires recovery.")
            .stringProperty("error", "Reason the operation was refused or needs recovery.").build();
    }

    @Override
    public ResponseType getResponseType()
    {
        return ResponseType.JSON;
    }

    @Override
    public String execute(Map<String, String> params)
    {
        String error = JsonUtils.requireArguments(params, "planId");
        if (error != null)
        {
            return error;
        }
        UUID id;
        try
        {
            id = UUID.fromString(JsonUtils.extractStringArgument(params, "planId"));
        }
        catch (IllegalArgumentException e)
        {
            return ToolResult.error("planId must be a UUID returned by preview_project_commit.").toJson();
        }
        try
        {
            return ProjectSnapshotService.confirmCommit(id).toJson();
        }
        catch (IOException e)
        {
            return ProjectSnapshotService.commitError(e).toJson();
        }
        catch (RuntimeException | LinkageError e)
        {
            return ToolResult.error("Project commit confirmation is unavailable. Check the authenticated Windows EDT server.")
                .toJson();
        }
    }
}
