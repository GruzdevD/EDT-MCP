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
import com.ditrix.edt.mcp.server.utils.snapshot.ChangeArtifacts;

public final class ReadChangeArtifactTool implements IMcpTool
{
    public static final String NAME = "read_change_artifact";
    @Override
    public String getName() { return NAME; }
    @Override
    public ResponseType getResponseType() { return ResponseType.JSON; }
    @Override
    public String getDescription()
    {
        return "List typed change documents or read their exact bytes and Git blob IDs. "
            + "Full parameters and examples: call get_tool_guide('" + NAME + "').";
    }

    static JsonSchemaBuilder scopeSchema()
    {
        return JsonSchemaBuilder.object().stringProperty("repoId", "Repository alias.", true)
            .stringProperty("projectName", "Exact EDT project.", true)
            .stringProperty("branch", "Authorized full local ref.", true)
            .stringProperty("changeId", "Existing change.", true);
    }

    @Override
    public String getInputSchema()
    {
        return scopeSchema().enumProperty("view", "Artifact operation.", true, "list", "read")
            .stringProperty("kind", "Registered document kind for read.")
            .stringProperty("itemId", "Capability or evidence ID for a nested document.")
            .stringProperty("revision", "Expected exact scoped revision.")
            .stringProperty("commitSha", "Optional reachable SHA. Defaults to the checked-out branch.").build();
    }

    @Override
    public String getOutputSchema()
    {
        return JsonSchemaBuilder.object().booleanProperty("success", "Whether reading succeeded.", true)
            .stringProperty("repoId", "Repository alias.").stringProperty("changeId", "Existing change.")
            .stringProperty("source", "workingTree or full SHA.").objectProperty("artifact", "Kind, ID, scoped revision, Git blob ID and digest.")
            .objectArrayProperty("artifacts", "Bounded list of available revisions.").stringProperty("base64", "Exact bytes.").build();
    }

    static ChangeArtifacts.Query query(Map<String, String> params)
    {
        return new ChangeArtifacts.Query(JsonUtils.extractStringArgument(params, "repoId"), JsonUtils.extractStringArgument(params, "projectName"), JsonUtils.extractStringArgument(params, "branch"),
            JsonUtils.extractStringArgument(params, "changeId"), JsonUtils.extractStringArgument(params, "commitSha"));
    }

    @Override
    public String execute(Map<String, String> params)
    {
        String missing = JsonUtils.requireArguments(params, "repoId", "projectName", "branch", "changeId", "view");
        if (missing != null) { return missing; }
        try
        {
            String kind = JsonUtils.extractStringArgument(params, "kind");
            var key = kind == null && JsonUtils.extractStringArgument(params, "itemId") == null ? null : new ChangeArtifacts.Key(kind, JsonUtils.extractStringArgument(params, "itemId"));
            return ChangeArtifacts.read(query(params), JsonUtils.extractStringArgument(params, "view"), key, JsonUtils.extractStringArgument(params, "revision")).toJson();
        }
        catch (IOException e) { return ToolResult.error(e.getMessage()).toJson(); }
        catch (RuntimeException | LinkageError e) { return ToolResult.error("Change artifact unavailable.").toJson(); }
    }
}
