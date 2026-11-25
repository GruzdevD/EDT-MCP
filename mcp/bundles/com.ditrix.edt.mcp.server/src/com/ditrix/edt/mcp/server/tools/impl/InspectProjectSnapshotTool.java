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

/** Reads an immutable page from one or two authorized retained snapshots. */
public final class InspectProjectSnapshotTool implements IMcpTool
{
    public static final String NAME = "inspect_project_snapshot";

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Read an immutable manifest or diff page from capture_project_snapshot, or compare two retained snapshots. "
            + "Full parameters and examples: call get_tool_guide('" + NAME + "').";
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object().stringProperty("snapshotId", "ID returned by capture_project_snapshot.", true)
            .enumProperty("view", "Page of version metadata or text diffs.", true, "manifest", "diff")
            .stringProperty("entryId", "Optional entry ID from the same snapshot and comparison.")
            .stringProperty("compareTo", "Optional older snapshot ID for the same scope.")
            .stringProperty("cursor", "Return nextCursor unchanged with the same query to continue.")
            .integerProperty("limit", "Entries per page, from 1 to " + ProjectSnapshotService.MAX_PAGE_SIZE + ".").build();
    }

    @Override
    public String getOutputSchema()
    {
        return JsonSchemaBuilder.object().booleanProperty("success", "Whether the page was read.", true)
            .stringProperty("snapshotId", "Retained snapshot ID.").stringProperty("expiresAt", "Retention deadline in UTC.")
            .enumProperty("modelStatus", "Evidence that the EDT model matches the sources.", "verified", "diverged", "unknown")
            .booleanProperty("verified", "Whether the snapshot is eligible for dependent verification.")
            .booleanProperty("yaxunitReady", "Whether the snapshot retains inputs for a bound YAxUnit run.")
            .objectArrayProperty("projects", "Captured project revisions and source fingerprints.")
            .enumProperty("view", "Returned page type.", "manifest", "diff")
            .integerProperty("total", "Number of entries matching the query.")
            .objectArrayProperty("entries", "Entries for this page.")
            .stringProperty("nextCursor", "Continuation cursor when another page exists.")
            .stringProperty("compareTo", "Older snapshot used for comparison, if requested.").build();
    }

    @Override
    public ResponseType getResponseType()
    {
        return ResponseType.JSON;
    }

    @Override
    public String execute(Map<String, String> params)
    {
        String error = JsonUtils.requireArguments(params, "snapshotId", "view");
        if (error != null)
        {
            return error;
        }
        String view = JsonUtils.extractStringArgument(params, "view");
        if (!"manifest".equals(view) && !"diff".equals(view))
        {
            return ToolResult.error("view must be manifest or diff.").toJson();
        }
        int defaultLimit = "diff".equals(view) ? 3 : 10;
        String suppliedLimit = JsonUtils.extractStringArgument(params, "limit");
        int limit = suppliedLimit == null ? defaultLimit : JsonUtils.extractIntArgument(params, "limit", -1);
        if (limit < 1 || limit > ProjectSnapshotService.MAX_PAGE_SIZE)
        {
            return ToolResult.error("limit must be an integer from 1 to " + ProjectSnapshotService.MAX_PAGE_SIZE + ".")
                .toJson();
        }
        UUID snapshotId;
        UUID entryId;
        UUID compareTo;
        try
        {
            snapshotId = id(JsonUtils.extractStringArgument(params, "snapshotId"));
            entryId = id(JsonUtils.extractStringArgument(params, "entryId"));
            compareTo = id(JsonUtils.extractStringArgument(params, "compareTo"));
        }
        catch (IllegalArgumentException e)
        {
            return ToolResult.error("snapshotId, entryId, and compareTo must be UUIDs returned by the snapshot tools.")
                .toJson();
        }
        String cursor = JsonUtils.extractStringArgument(params, "cursor");
        try
        {
            return ProjectSnapshotService.inspect(snapshotId, view, entryId, compareTo, cursor, limit).toJson();
        }
        catch (IOException e)
        {
            return ToolResult.error(e.getMessage()).toJson();
        }
        catch (RuntimeException | LinkageError e)
        {
            return ToolResult.error("Snapshot inspection is unavailable. Capture a new snapshot and retry.").toJson();
        }
    }

    private static UUID id(String value)
    {
        if (value == null)
        {
            return null;
        }
        UUID result = UUID.fromString(value);
        if (!result.toString().equalsIgnoreCase(value))
        {
            throw new IllegalArgumentException();
        }
        return result;
    }
}
