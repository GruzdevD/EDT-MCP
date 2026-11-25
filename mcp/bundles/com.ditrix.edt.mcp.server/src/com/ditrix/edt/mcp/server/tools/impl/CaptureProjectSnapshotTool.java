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

import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.utils.snapshot.ProjectSnapshotService;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

/** Captures authorized project source versions on the dedicated EDT host. */
public final class CaptureProjectSnapshotTool implements IMcpTool
{
    public static final String NAME = "capture_project_snapshot";

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Capture HEAD, index, and disk versions of an authorized EDT project's sources for review without a local clone. "
            + "Read it with inspect_project_snapshot or request build inputs with forYaxunit. Full parameters and examples: call get_tool_guide('"
            + NAME + "').";
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object().stringProperty("projectName", "Exact EDT project name enabled on the server.", true)
            .stringArrayProperty("includedProjects", "Exact names of explicitly included EDT extension projects.")
            .booleanProperty("forYaxunit", "Retain complete disk inputs for an isolated YAxUnit build.").build();
    }

    @Override
    public String getOutputSchema()
    {
        return JsonSchemaBuilder.object().booleanProperty("success", "Whether capture succeeded.", true)
            .stringProperty("snapshotId", "ID understood by inspect_project_snapshot and snapshot-bound run_yaxunit_tests.")
            .stringProperty("expiresAt", "Retention deadline in UTC.")
            .enumProperty("modelStatus", "Evidence that the EDT model matches the sources.", "verified", "diverged", "unknown")
            .booleanProperty("verified", "Whether the snapshot is eligible for dependent verification.")
            .booleanProperty("yaxunitReady", "Whether the complete isolated-build inputs were retained.")
            .objectArrayProperty("projects", "Captured project names, Git revisions, and source fingerprints.").build();
    }

    @Override
    public ResponseType getResponseType()
    {
        return ResponseType.JSON;
    }

    @Override
    public String execute(Map<String, String> params)
    {
        String error = JsonUtils.requireArguments(params, "projectName");
        if (error != null)
        {
            return error;
        }
        String project = JsonUtils.extractStringArgument(params, "projectName");
        String included = JsonUtils.extractStringArgument(params, "includedProjects");
        List<String> extensions;
        try
        {
            extensions = parseIncluded(included);
        }
        catch (RuntimeException e)
        {
            return ToolResult.error("includedProjects must be an array of exact EDT project names, up to 15 extensions.")
                .toJson();
        }
        try
        {
            boolean forYaxunit = JsonUtils.extractBooleanArgument(params, "forYaxunit", false);
            return ProjectSnapshotService.capture(project, extensions, forYaxunit).toJson();
        }
        catch (IOException e)
        {
            return ToolResult.error(e.getMessage()).toJson();
        }
        catch (RuntimeException | LinkageError e)
        {
            return ToolResult.error("Snapshot capture is unavailable. Check the authenticated Windows EDT configuration.")
                .toJson();
        }
    }

    private static List<String> parseIncluded(String value)
    {
        if (value == null)
        {
            return List.of();
        }
        if (value.length() > 8192)
        {
            throw new IllegalArgumentException();
        }
        var array = JsonParser.parseString(value).getAsJsonArray();
        if (array.size() > 15)
        {
            throw new IllegalArgumentException();
        }
        List<String> result = new ArrayList<>(array.size());
        for (JsonElement name : array)
        {
            if (!name.isJsonPrimitive() || !name.getAsJsonPrimitive().isString() || name.getAsString().isBlank())
            {
                throw new IllegalArgumentException();
            }
            result.add(name.getAsString());
        }
        return List.copyOf(result);
    }
}
