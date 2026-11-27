/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils.snapshot;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import com.ditrix.edt.mcp.server.protocol.GsonProvider;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.utils.snapshot.ProjectSourceManifest.Entry;
import com.ditrix.edt.mcp.server.utils.snapshot.ProjectSourceManifest.Project;
import com.ditrix.edt.mcp.server.utils.snapshot.ProjectSourceManifest.Version;

/** Pages a project and side from immutable bytes. Progress uses byte offsets for content and search. */
public final class ProjectVersionReader
{
    public static final int PAGE_BYTES = 16 * 1024;
    public static final int PAGE_ROWS = 20;
    public static final int SCAN_BYTES = 1024 * 1024;
    public static final int FILE_BYTES = ProjectSourceManifest.MAX_FILE_BYTES;

    private ProjectVersionReader() { }

    public record Query(String repoId, String projectName, UUID snapshotId, String side, String scope,
        String view, String fileId, String literal, String cursor, String commitSha, String baseSha, String branch)
    {
        public Query(String repoId, String projectName, UUID snapshotId, String side, String scope,
            String view, String fileId, String literal, String cursor)
        {
            this(repoId, projectName, snapshotId, side, scope, view, fileId, literal, cursor, null, null, null);
        }
    }

    private record Row(String fileId, String path, String contentId, int bytes, Integer byteOffset) { }

    static ToolResult read(ProjectSnapshotPages cursors, Query q, Project project) throws IOException
    {
        return read(cursors, q, project, "");
    }

    static ToolResult read(ProjectSnapshotPages cursors, Query q, Project project, String policyBinding) throws IOException
    {
        String scope = q.scope() == null ? "projectSources" : q.scope();
        if (!List.of("head", "index", "disk", "commit", "candidate", "baseline").contains(q.side())
            || !List.of("manifest", "diff", "content", "literalSearch").contains(q.view())
            || !List.of("projectSources", "perimeter", "repository").contains(scope)
            || "content".equals(q.view()) != (q.fileId() != null)
            || "literalSearch".equals(q.view()) != (q.literal() != null)
            || q.literal() != null && (q.literal().isEmpty() || q.literal().length() > 512))
        {
            throw new IOException("Invalid version view, scope, side or selection.");
        }
        String binding = GsonProvider.toJson(Arrays.asList("projectVersion", q.repoId(), q.projectName(),
            q.snapshotId(), q.commitSha(), q.baseSha(), q.branch(), q.side(), scope, q.view(), q.fileId(), q.literal(), policyBinding));
        int start = q.cursor() == null ? 0 : cursors.decode(q.cursor(), binding);
        ToolResult result = ToolResult.success().put("repoId", q.repoId()).put("projectName", project.name())
            .put("side", q.side()).put("scope", scope);
        String versionId;
        if (q.snapshotId() != null)
        {
            versionId = q.snapshotId().toString();
            result.put(switch (q.side()) { case "candidate" -> "planId"; case "baseline" -> "baselineId"; default -> "snapshotId"; }, versionId);
        }
        else
        {
            versionId = q.commitSha();
            result.put("commitSha", versionId).put("branch", q.branch());
        }
        if ("repository".equals(scope) || ("perimeter".equals(scope) && project.perimeter().isEmpty()))
        {
            return result.put("complete", false).put("reason", "scope_unavailable");
        }
        List<Object> rows = new ArrayList<>();
        int position = 0;
        long scanned = 0;
        byte[] needle = q.literal() == null ? null : q.literal().getBytes(StandardCharsets.UTF_8);
        for (Entry entry : "perimeter".equals(scope) ? project.perimeter() : project.entries())
        {
            Version version = switch (q.side()) { case "head" -> entry.head(); case "index" -> entry.index(); default -> entry.disk(); };
            if ("diff".equals(q.view()))
            {
                Version before = entry.head();
                if (!java.util.Objects.equals(before == null ? null : before.contentId(), version == null ? null : version.contentId()))
                {
                    if (position++ < start)
                    {
                        continue;
                    }
                    var delta = ProjectSnapshotPages.patch("comparison", before, version,
                        "disk".equals(q.side()) && project.rawDiskComparison());
                    String handle = fileId(versionId, q, entry);
                    rows.add(new Diff(entry.path(), handle, before == null ? null : before.contentId(),
                        version == null ? null : version.contentId(), delta));
                    // A single patch can consume the entire byte budget.
                    return finish(result.put("entries", rows), cursors, binding, position);
                }
                continue;
            }
            if (version == null)
            {
                continue;
            }
            String id = fileId(versionId, q, entry);
            if ("content".equals(q.view()))
            {
                if (id.equals(q.fileId()))
                {
                    byte[] bytes = version.bytes();
                    int end = Math.min(bytes.length, start + PAGE_BYTES);
                    result.put("fileId", id).put("path", entry.path()).put("contentId", version.contentId())
                        .put("bytes", bytes.length).put("byteOffset", start)
                        .put("base64", Base64.getEncoder().encodeToString(Arrays.copyOfRange(bytes, start, end)));
                    return finish(result, cursors, binding, end < bytes.length ? end : -1);
                }
                continue;
            }
            if (needle == null)
            {
                if (position >= start)
                {
                    if (rows.size() == PAGE_ROWS)
                    {
                        return finish(result.put("entries", rows), cursors, binding, position);
                    }
                    rows.add(new Row(id, entry.path(), version.contentId(), version.size(), null));
                }
                position++;
                continue;
            }
            int size = version.size();
            if (position + size > start)
            {
                byte[] bytes = version.bytes();
                for (int offset = Math.max(0, start - position); offset <= size - needle.length; offset++)
                {
                    if (scanned >= SCAN_BYTES || rows.size() >= PAGE_ROWS)
                    {
                        return finish(result.put("entries", rows), cursors, binding, position + offset);
                    }
                    scanned++;
                    if (Arrays.equals(bytes, offset, offset + needle.length, needle, 0, needle.length))
                    {
                        rows.add(new Row(id, entry.path(), version.contentId(), size, offset));
                    }
                }
            }
            position = Math.addExact(position, size);
        }
        if ("content".equals(q.view()))
        {
            throw new IOException("File is unavailable for this version and scope.");
        }
        return finish(result.put("entries", rows), cursors, binding, -1);
    }

    private static ToolResult finish(ToolResult result, ProjectSnapshotPages cursors, String binding, int next)
    {
        result.put("complete", next < 0);
        return next < 0 ? result : result.put("nextCursor", cursors.encode(next, binding));
    }

    private record Diff(String path, String fileId, String beforeId, String afterId, ProjectSnapshotPages.Delta patch) { }

    private static String fileId(String versionId, Query q, Entry entry)
    {
        return UUID.nameUUIDFromBytes(GsonProvider.toJson(List.of(versionId, q.repoId(), q.projectName(), q.side(),
            q.scope() == null ? "projectSources" : q.scope(), entry.path())).getBytes(StandardCharsets.UTF_8)).toString();
    }
}
