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

public final class StageVerifiedEntriesTool implements IMcpTool
{
    public static final String NAME = "stage_verified_entries";
    @Override
    public String getName() { return NAME; }
    @Override
    public ResponseType getResponseType() { return ResponseType.JSON; }
    @Override
    public String getDescription()
    {
        return "Stage selected disk versions from a project snapshot without committing. "
            + "Full parameters and examples: call get_tool_guide('" + NAME + "').";
    }
    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object().stringProperty("repoId", "Authorized repository alias.", true)
            .stringProperty("snapshotId", "Source snapshot.", true)
            .stringArrayProperty("entryIds", "Distinct selected change IDs from inspect_project_snapshot.", true).build();
    }
    @Override
    public String getOutputSchema()
    {
        return JsonSchemaBuilder.object().booleanProperty("success", "Whether the index was published.", true)
            .stringProperty("snapshotId", "Input capture.").stringProperty("repoId", "Repository alias.")
            .stringProperty("parentSha", "Expected HEAD.").stringProperty("branch", "Local branch.")
            .objectArrayProperty("entries", "Selected paths and exact indexBlob/diskBlob Git IDs.").build();
    }
    @Override
    public String execute(Map<String, String> params)
    {
        String missing = JsonUtils.requireArguments(params, "snapshotId", "repoId", "entryIds");
        if (missing != null) { return missing; }
        try
        {
            return ProjectSnapshotService.stage(UUID.fromString(JsonUtils.extractStringArgument(params, "snapshotId")),
                JsonUtils.extractStringArgument(params, "repoId"), PreviewProjectCommitTool.entryIds(params)).toJson();
        }
        catch (IOException e)
        {
            return ProjectSnapshotService.commitError(e).toJson();
        }
        catch (RuntimeException | LinkageError e)
        {
            return ToolResult.error("Stage unavailable. Supply a snapshot and 1 to 100 distinct entry UUIDs.").toJson();
        }
    }
}
