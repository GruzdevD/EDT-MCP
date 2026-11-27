/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.utils.snapshot.ProjectSnapshotService;
import com.ditrix.edt.mcp.server.utils.snapshot.BuildReview;
import com.google.gson.JsonParser;

/** Previews a selected commit from a retained project source snapshot. */
public final class PreviewProjectCommitTool implements IMcpTool
{
    public static final String NAME = "preview_project_commit";

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Preview the exact Git tree for selected entries from an authorized project snapshot. "
            + "Review the plan before calling confirm_project_commit. Full parameters and examples: call get_tool_guide('"
            + NAME + "').";
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object().stringProperty("snapshotId", "ID returned by capture_project_snapshot.", true)
            .stringArrayProperty("entryIds", "1 to 100 distinct changed entry IDs from the snapshot.", true)
            .objectProperty("buildReview", "Build scope: repoId, projectName, changeId, optional exact artifacts and pinned dependencies.")
            .stringProperty("message", "Final commit message, up to 8192 UTF-8 bytes.", true).build();
    }

    @Override
    public String getOutputSchema()
    {
        return JsonSchemaBuilder.object().booleanProperty("success", "Whether the plan was created.", true)
            .stringProperty("planId", "Plan ID for confirm_project_commit.")
            .stringProperty("expiresAt", "Plan expiration in UTC.")
            .enumProperty("modelStatus", "Captured EDT model status.", "verified", "unknown")
            .stringProperty("branch", "Full local branch ref.")
            .stringProperty("parentSha", "Expected parent commit.")
            .stringProperty("treeId", "Exact Git tree to commit.")
            .objectProperty("buildReview", "Build coordinates, selected artifact revisions and project pins.")
            .stringProperty("candidateInputsId", "Retained build inputs for this plan.")
            .stringProperty("manifestId", "Digest of ordered full project fingerprints.")
            .objectArrayProperty("inputs", "Ordered project revisions, trees and fingerprints.")
            .stringProperty("message", "Commit message retained in the plan.")
            .objectArrayProperty("entries", "Selected paths and content IDs, with both paths for identified renames.")
            .stringProperty("error", "Reason the plan was refused.").build();
    }

    @Override
    public ResponseType getResponseType()
    {
        return ResponseType.JSON;
    }

    @Override
    public String execute(Map<String, String> params)
    {
        String error = JsonUtils.requireArguments(params, "snapshotId", "entryIds", "message");
        if (error != null)
        {
            return error;
        }
        UUID snapshotId;
        List<UUID> entryIds;
        try
        {
            snapshotId = UUID.fromString(JsonUtils.extractStringArgument(params, "snapshotId"));
            entryIds = entryIds(params);
        }
        catch (RuntimeException e)
        {
            return ToolResult.error("Provide a snapshot UUID and an array of 1 to 100 distinct entry UUIDs.").toJson();
        }
        try
        {
            return ProjectSnapshotService.previewCommit(snapshotId, entryIds,
                JsonUtils.extractStringArgument(params, "message"), BuildReview.parse(JsonUtils.extractStringArgument(params, "buildReview"))).toJson();
        }
        catch (IOException e)
        {
            return ProjectSnapshotService.commitError(e).toJson();
        }
        catch (RuntimeException | LinkageError e)
        {
            return ToolResult.error("Project commit preview is unavailable. Check the authenticated Windows EDT server.")
                .toJson();
        }
    }

    static List<UUID> entryIds(Map<String, String> params)
    {
        List<UUID> entryIds = new ArrayList<>();
        String encoded = JsonUtils.extractStringArgument(params, "entryIds");
        if (encoded.length() > 8192)
        {
            throw new IllegalArgumentException();
        }
        for (var item : JsonParser.parseString(encoded).getAsJsonArray())
        {
            if (!item.isJsonPrimitive() || !item.getAsJsonPrimitive().isString())
            {
                throw new IllegalArgumentException();
            }
            entryIds.add(UUID.fromString(item.getAsString()));
        }
        if (entryIds.isEmpty() || entryIds.size() > 100 || entryIds.stream().distinct().count() != entryIds.size())
        {
            throw new IllegalArgumentException();
        }
        return entryIds;
    }
}
