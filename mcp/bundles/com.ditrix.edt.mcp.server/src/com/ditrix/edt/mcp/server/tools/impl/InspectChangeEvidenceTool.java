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
import com.ditrix.edt.mcp.server.utils.snapshot.ReviewEvidence;

public final class InspectChangeEvidenceTool implements IMcpTool
{
    public static final String NAME = "inspect_change_evidence";
    @Override public String getName() { return NAME; }
    @Override public ResponseType getResponseType() { return ResponseType.JSON; }
    @Override public String getDescription()
    {
        return "Recover retained build results, commit receipts and baseline comparisons. Full parameters and examples: call get_tool_guide('" + NAME + "').";
    }
    @Override public String getInputSchema()
    {
        return ReadChangeArtifactTool.scopeSchema().stringProperty("cycle","Review cycle identifier.")
            .enumProperty("view","Evidence operation.",true,"list","job","files","content","compare","receipts")
            .stringProperty("jobId","Retained job UUID.").stringProperty("group","production, tests or documents.")
            .stringProperty("digest","Listed content or report SHA-256.").stringProperty("targetSha","Reachable full target SHA.")
            .objectProperty("documentRevisions","Exact normative revision map for document comparison.")
            .stringProperty("cursor","Continuation returned for this request.").build();
    }
    @Override public String getOutputSchema()
    {
        return JsonSchemaBuilder.object().booleanProperty("success","Whether the operation succeeded.",true)
            .objectArrayProperty("entries","Page of scoped evidence.").stringProperty("base64","Exact content bytes.")
            .booleanProperty("complete","Whether the page is final.").stringProperty("nextCursor","Continuation.").build();
    }
    @Override public String execute(Map<String,String> params)
    {
        String missing = JsonUtils.requireArguments(params,"repoId","projectName","branch","changeId","view");
        if (missing != null) { return missing; }
        try
        {
            var scope = ReadChangeArtifactTool.query(params);
            String id = JsonUtils.extractStringArgument(params,"jobId");
            String revisions = JsonUtils.extractStringArgument(params,"documentRevisions");
            Map<String,String> selected = null;
            if (revisions != null)
            {
                selected = new java.util.TreeMap<>();
                for (var entry : com.google.gson.JsonParser.parseString(revisions).getAsJsonObject().entrySet())
                {
                    if (!entry.getValue().isJsonPrimitive() || !entry.getValue().getAsJsonPrimitive().isString())
                    { throw new IOException("Expected exact revision strings."); }
                    selected.put(entry.getKey(),entry.getValue().getAsString());
                }
            }
            return ReviewEvidence.inspect(new ReviewEvidence.Request(scope.repoId(),scope.projectName(),scope.branch(),scope.changeId(),
                JsonUtils.extractStringArgument(params,"cycle"),JsonUtils.extractStringArgument(params,"view"),id == null ? null : UUID.fromString(id),
                JsonUtils.extractStringArgument(params,"group"),JsonUtils.extractStringArgument(params,"digest"),JsonUtils.extractStringArgument(params,"targetSha"),
                selected,
                JsonUtils.extractStringArgument(params,"cursor"))).toJson();
        }
        catch (IOException e) { return ToolResult.error(e.getMessage()).toJson(); }
        catch (RuntimeException | LinkageError e) { return ToolResult.error("Evidence unavailable.").toJson(); }
    }
}
