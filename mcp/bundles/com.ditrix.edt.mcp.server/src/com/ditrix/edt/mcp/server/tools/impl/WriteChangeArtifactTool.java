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
import com.ditrix.edt.mcp.server.utils.snapshot.ChangeArtifactWriter;
import com.ditrix.edt.mcp.server.utils.snapshot.ChangeArtifacts;

public final class WriteChangeArtifactTool implements IMcpTool
{
    public static final String NAME = "write_change_artifact";
    @Override
    public String getName() { return NAME; }
    @Override
    public ResponseType getResponseType() { return ResponseType.JSON; }
    @Override
    public String getDescription()
    {
        return "Create a typed change document, update an exact revision or append an idempotent build event. "
            + "Full parameters and examples: call get_tool_guide('" + NAME + "').";
    }
    @Override
    public String getInputSchema()
    {
        return ReadChangeArtifactTool.scopeSchema().enumProperty("operation","Requested mutation.",true,"create","put","checkbox","append")
            .stringProperty("kind","Registered document kind.",true).stringProperty("itemId","Nested document ID.")
            .stringProperty("expectedRevision","Scoped revision or absent for initial creation.",true)
            .stringProperty("operationId","Canonical UUID for append retries.").stringProperty("base64","Exact payload bytes.",true).build();
    }
    @Override
    public String getOutputSchema()
    {
        return JsonSchemaBuilder.object().booleanProperty("success","Whether the operation succeeded.",true)
            .objectProperty("artifact","Resulting scoped revision and content hashes.")
            .stringProperty("operationId","Append receipt key.").build();
    }
    @Override
    public String execute(Map<String,String> params)
    {
        String missing = JsonUtils.requireArguments(params,"repoId","projectName","branch","changeId","operation","kind","expectedRevision","base64");
        if (missing != null) { return missing; }
        try
        {
            var key = new ChangeArtifacts.Key(JsonUtils.extractStringArgument(params, "kind"), JsonUtils.extractStringArgument(params, "itemId"));
            var request = new ChangeArtifactWriter.Request(ReadChangeArtifactTool.query(params), key,
                JsonUtils.extractStringArgument(params, "operation"), JsonUtils.extractStringArgument(params, "expectedRevision"),
                JsonUtils.extractStringArgument(params, "operationId"), JsonUtils.extractStringArgument(params, "base64"));
            return ChangeArtifactWriter.write(request).toJson();
        }
        catch (IOException e) { return ToolResult.error(e.getMessage()).toJson(); }
        catch (RuntimeException | LinkageError e) { return ToolResult.error("Invalid artifact input or unavailable transactional storage.").toJson(); }
    }
}
