/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils.snapshot;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Base64;
import java.util.Set;
import java.util.UUID;

import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.utils.snapshot.ChangeArtifacts.Key;
import com.ditrix.edt.mcp.server.utils.snapshot.ChangeArtifacts.Query;

public final class ChangeArtifactWriter
{
    private ChangeArtifactWriter() { }

    public record Request(Query scope, Key key, String operation, String expectedRevision, String operationId, String base64) { }

    static final class Conflict extends IOException
    {
        private static final long serialVersionUID = 1L;
        Conflict() { super("Artifact revision conflict. Read the current revision before retrying."); }
    }

    static String permission(Key key, String operation) throws IOException
    {
        key.relative();
        if ("append".equals(operation) && "iterationLog".equals(key.kind())) { return "append"; }
        if ("checkbox".equals(operation) && "tasks".equals(key.kind())) { return "report"; }
        if (!Set.of("create", "put").contains(operation)) { throw SnapshotSourcePolicy.denied(); }
        return switch (key.kind())
        {
            case "boundary", "raw", "manifest", "reviewBody" -> {
                if (!"create".equals(operation)) { throw SnapshotSourcePolicy.denied(); }
                yield "report";
            }
            case "buildReview", "metadataReview" -> "report";
            case "iterationLog" -> throw SnapshotSourcePolicy.denied();
            default -> "write";
        };
    }

    public static ToolResult write(Request request) throws IOException
    {
        Query q = request.scope();
        String permission = permission(request.key(), request.operation());
        if (q.commitSha() != null || !ChangeArtifacts.validId(q.changeId()) || request.expectedRevision() == null
            || request.base64() == null || request.base64().length() > (ChangeArtifacts.MAX_BYTES + 2) / 3 * 4
            || "append".equals(request.operation()) != (request.operationId() != null))
        {
            throw new IOException("Invalid working-tree artifact request.");
        }
        byte[] payload = Base64.getDecoder().decode(request.base64());
        if (payload.length > ChangeArtifacts.MAX_BYTES) { throw new IOException("Document exceeds byte limit."); }
        if (request.operationId() != null && !UUID.fromString(request.operationId()).toString().equals(request.operationId()))
        {
            throw new IOException("Expected a canonical operation UUID.");
        }
        return ProjectReviewHistory.withRepository(q.repoId(), q.projectName(), q.branch(), (repo, scoped) -> {
            ChangeArtifacts.require(scoped.grant().review(), q.changeId(), permission);
            if (!q.branch().equals(repo.getFullBranch())) { throw SnapshotSourcePolicy.denied(); }
            var tree = WindowsSourceTree.load();
            Path change = repo.getWorkTree().toPath().resolve(ChangeArtifacts.prefix(q.changeId()));
            var disk = new ChangeArtifacts.Disk(tree,repo,change);
            try (var parent = disk.open(change,false,false))
            {
                // Keep the existing change pinned, including when creating an evidence child.
                if (parent.readIfPresent("proposal.md", ChangeArtifacts.MAX_BYTES) == null) { throw SnapshotSourcePolicy.denied(); }
                return writeAt(disk, request, payload);
            }
        });
    }

    static ToolResult writeAt(ChangeArtifacts.Disk disk, Request r, byte[] payload) throws IOException
    {
        String relative = r.key().relative();
        boolean append = "append".equals(r.operation());
        boolean absent = "absent".equals(r.expectedRevision());
        if (!append && absent != "create".equals(r.operation())) { throw new Conflict(); }
        var tree = disk.tree();
        Path file = disk.change().resolve(relative);
        try (var directory = disk.open(file.getParent(), absent, false))
        {
            var receipt = new ChangeArtifacts.Revision[1];
            WindowsSourceTree.Replacement update = previous -> {
                ChangeArtifacts.Revision old = previous == null ? null : ChangeArtifacts.revision(r.scope(), r.key(), previous);
                byte[] next;
                if (append)
                {
                    byte[] record = ("<!-- edt-build-event " + r.operationId() + " " + Base64.getEncoder().encodeToString(payload)
                        + " -->\n").getBytes(StandardCharsets.US_ASCII);
                    byte[] prior = previous == null ? new byte[0] : previous;
                    String log = new String(prior, StandardCharsets.ISO_8859_1);
                    String marker = "\n<!-- edt-build-event " + r.operationId() + " ";
                    int offset = ("\n" + log).indexOf(marker);
                    if (offset >= 0)
                    {
                        int end = offset + record.length;
                        if (end > prior.length || !Arrays.equals(prior, offset, end, record, 0, record.length))
                        {
                            throw new Conflict();
                        }
                        receipt[0] = ChangeArtifacts.revision(r.scope(), r.key(), Arrays.copyOf(prior, end));
                        return prior;
                    }
                    boolean newline = prior.length > 0 && prior[prior.length - 1] != 10;
                    int start = prior.length + (newline ? 1 : 0);
                    next = Arrays.copyOf(prior, start + record.length);
                    if (newline) { next[start - 1] = 10; }
                    System.arraycopy(record, 0, next, start, record.length);
                }
                else { next = payload; }
                if (old == null ? !absent : !old.revision().equals(r.expectedRevision())) { throw new Conflict(); }
                if ("checkbox".equals(r.operation())) { requireCheckbox(previous, payload); }
                receipt[0] = ChangeArtifacts.revision(r.scope(), r.key(), next);
                return next;
            };
            if (append && absent)
            {
                try { tree.replace(directory, file.getFileName().toString(), true, ChangeArtifacts.MAX_BYTES, update); }
                catch (IOException first)
                {
                    tree.replace(directory, file.getFileName().toString(), false, ChangeArtifacts.MAX_BYTES, update);
                }
            }
            else { tree.replace(directory, file.getFileName().toString(), absent, ChangeArtifacts.MAX_BYTES, update); }
            return ToolResult.success().put("artifact", receipt[0]).put("operationId", r.operationId());
        }
    }

    private static void requireCheckbox(byte[] original, byte[] replacement) throws IOException
    {
        if (original == null || original.length != replacement.length) { throw SnapshotSourcePolicy.denied(); }
        for (int i = 0; i < original.length; i++)
        {
            if (original[i] == replacement[i]) { continue; }
            if (i < 3 || i + 2 >= original.length || i > 3 && original[i-4] != 10
                || original[i-3] != '-' || original[i-2] != ' ' || original[i-1] != '['
                || original[i] != ' ' || replacement[i] != 'x' || original[i+1] != ']' || original[i+2] != ' ')
            {
                throw SnapshotSourcePolicy.denied();
            }
        }
    }
}
