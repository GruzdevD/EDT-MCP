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
import com.ditrix.edt.mcp.server.utils.snapshot.ProjectVersionReader;

public final class InspectProjectVersionTool implements IMcpTool
{
    public static final String NAME = "inspect_project_version";

    @Override
    public String getName() { return NAME; }

    @Override
    public ResponseType getResponseType() { return ResponseType.JSON; }

    @Override
    public String getDescription()
    {
        return "Read immutable project sources or find a UTF-8 literal, including unchanged files. "
            + "Full parameters and examples: call get_tool_guide('" + NAME + "').";
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object().stringProperty("repoId", "Repository alias from server policy.", true)
            .stringProperty("projectName", "Exact EDT project.", true)
            .stringProperty("snapshotId", "Retained capture ID, valid for ten minutes.")
            .stringProperty("commitSha", "Full reachable SHA in place of snapshotId.")
            .stringProperty("baselineId","Retained first failed or broken job UUID.").stringProperty("planId", "Retained candidate plan in place of a snapshot or commit.")
            .stringProperty("treeId", "Exact preview tree for planId.")
            .stringProperty("branch", "Authorized local branch ref for commitSha.")
            .stringProperty("baseSha", "Optional reachable comparison SHA for commit diff.")
            .enumProperty("side", "Required for snapshot; commit for commitSha.", "head", "index", "disk", "commit")
            .enumProperty("scope", "Required coverage.", "projectSources", "perimeter", "repository")
            .enumProperty("view", "Read mode.", true, "manifest", "diff", "content", "literalSearch")
            .stringProperty("fileId", "File handle from this version for content.")
            .stringProperty("literal", "Exact UTF-8 search text, up to 512 UTF-16 units.")
            .stringProperty("cursor", "Opaque continuation for the same request.").build();
    }

    @Override
    public String getOutputSchema()
    {
        return JsonSchemaBuilder.object().booleanProperty("success", "Whether the read succeeded.", true)
            .booleanProperty("complete", "Requested scope exhausted. False with nextCursor or reason.")
            .stringProperty("repoId", "Repository alias.").stringProperty("projectName", "EDT project.")
            .stringProperty("snapshotId", "Immutable snapshot.").stringProperty("side", "Selected side.")
            .stringProperty("commitSha", "Requested commit.").stringProperty("treeId", "Committed tree.")
            .stringProperty("baselineId","Retained first failed or broken job UUID.").stringProperty("planId", "Candidate plan.").stringProperty("manifestId", "Ordered project inputs digest.")
            .stringProperty("baseSha", "Compared commit.").stringProperty("branch", "Authorized local ref.")
            .stringProperty("scope", "Requested scope.").stringProperty("expiresAt", "UTC retention deadline.")
            .stringProperty("nextCursor", "Continue using the same query.")
            .stringProperty("reason", "Cause of incomplete coverage.")
            .objectArrayProperty("entries", "Up to " + ProjectVersionReader.PAGE_ROWS + " files or literal hits per page; scan budget "
                + ProjectVersionReader.SCAN_BYTES + " byte positions.")
            .stringProperty("path", "Project-relative path.").stringProperty("fileId", "Scoped file handle.")
            .stringProperty("contentId", "Digest of entire file.").integerProperty("bytes", "Full size; capture maximum "
                + ProjectVersionReader.FILE_BYTES + " bytes.").integerProperty("byteOffset", "Zero-based byte offset.")
            .stringProperty("base64", "Exact slice, up to " + ProjectVersionReader.PAGE_BYTES + " decoded bytes.").build();
    }

    @Override
    public String execute(Map<String, String> params)
    {
        String missing = JsonUtils.requireArguments(params, "repoId", "projectName", "view");
        if (missing != null)
        {
            return missing;
        }
        try
        {
            String input = JsonUtils.extractStringArgument(params, "snapshotId");
            String commit = JsonUtils.extractStringArgument(params, "commitSha");
            String plan = JsonUtils.extractStringArgument(params, "planId");
            String baseline = JsonUtils.extractStringArgument(params,"baselineId");
            String tree = JsonUtils.extractStringArgument(params, "treeId");
            if (java.util.stream.Stream.of(input, commit, plan, baseline).filter(java.util.Objects::nonNull).count() != 1
                || (plan == null) != (tree == null))
            {
                return ToolResult.error("Choose one snapshot, reachable commit, or planId with treeId.").toJson();
            }
            UUID id = input == null ? null : UUID.fromString(input);
            if (id != null && !id.toString().equalsIgnoreCase(input))
            {
                throw new IllegalArgumentException();
            }
            String side = JsonUtils.extractStringArgument(params, "side");
            if ((plan != null || baseline != null) && (side != null || params.containsKey("branch") || params.containsKey("baseSha")))
            {
                return ToolResult.error("A plan selects its own tree and branch.").toJson();
            }
            if (id != null && side == null)
            {
                return ToolResult.error("Specify a snapshot side.").toJson();
            }
            var query = new ProjectVersionReader.Query(JsonUtils.extractStringArgument(params, "repoId"),
                JsonUtils.extractStringArgument(params, "projectName"), baseline != null ? UUID.fromString(baseline) : plan == null ? id : UUID.fromString(plan),
                baseline != null ? "baseline" : plan != null ? "candidate" : side == null ? "commit" : side,
                JsonUtils.extractStringArgument(params, "scope"), JsonUtils.extractStringArgument(params, "view"),
                JsonUtils.extractStringArgument(params, "fileId"), JsonUtils.extractStringArgument(params, "literal"),
                JsonUtils.extractStringArgument(params, "cursor"), commit, JsonUtils.extractStringArgument(params, "baseSha"),
                JsonUtils.extractStringArgument(params, "branch"));
            return (baseline != null ? com.ditrix.edt.mcp.server.utils.snapshot.ReviewEvidence.inspectBaseline(query) : plan == null ? ProjectSnapshotService.inspectVersion(query)
                : ProjectSnapshotService.inspectCandidate(query.snapshotId(),tree,query)).toJson();
        }
        catch (IllegalArgumentException e)
        {
            return ToolResult.error("Expected a snapshot UUID.").toJson();
        }
        catch (IOException e)
        {
            return ToolResult.error(e.getMessage()).toJson();
        }
        catch (RuntimeException | LinkageError e)
        {
            return ToolResult.error("Version inspection is unavailable.").toJson();
        }
    }
}
