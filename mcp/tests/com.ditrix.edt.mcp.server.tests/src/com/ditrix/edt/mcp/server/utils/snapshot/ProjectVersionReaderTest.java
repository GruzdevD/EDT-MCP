/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils.snapshot;

import static org.junit.Assert.*;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.Test;

import com.ditrix.edt.mcp.server.utils.snapshot.ProjectSourceManifest.Entry;
import com.ditrix.edt.mcp.server.utils.snapshot.ProjectSourceManifest.Project;
import com.ditrix.edt.mcp.server.utils.snapshot.ProjectSourceManifest.Version;
import com.ditrix.edt.mcp.server.utils.snapshot.ProjectVersionReader.Query;
import com.ditrix.edt.mcp.server.utils.snapshot.SnapshotSourcePolicy.Grant;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class ProjectVersionReaderTest
{
    private final ProjectSnapshotPages cursors = new ProjectSnapshotPages();
    private final UUID version = UUID.randomUUID();
    private final byte[] bytes = new byte[ProjectVersionReader.PAGE_BYTES + 1];
    private final Path root = Path.of("work");
    private final Project project;

    public ProjectVersionReaderTest()
    {
        bytes[bytes.length - 1] = 42;
        var source = new Version(bytes);
        project = new Project("Base", "refs/heads/main", "head", List.of(new Entry("src/Caller.bsl", source,
            source, source)), false, null, root);
    }

    private JsonObject read(String side, String scope, String view, String file, String literal, String cursor)
        throws IOException
    {
        return JsonParser.parseString(ProjectVersionReader.read(cursors, new Query("repo", project.name(),
            version, side, scope, view, file, literal, cursor), project).toJson()).getAsJsonObject();
    }

    @Test
    public void unchangedFileHasExactImmutablePagesBoundToScopeAndSide() throws IOException
    {
        String file = read("disk", null, "manifest", null, null, null).getAsJsonArray("entries").get(0)
            .getAsJsonObject().get("fileId").getAsString();
        var page = read("disk", null, "content", file, null, null);
        assertFalse(page.get("complete").getAsBoolean());
        assertEquals(ProjectVersionReader.PAGE_BYTES, Base64.getDecoder().decode(page.get("base64").getAsString()).length);
        String cursor = page.get("nextCursor").getAsString();
        bytes[bytes.length - 1] = 7;
        var tail = read("disk", null, "content", file, null, cursor);
        assertArrayEquals(new byte[]{42}, Base64.getDecoder().decode(tail.get("base64").getAsString()));
        assertTrue(tail.get("complete").getAsBoolean());
        assertThrows(IOException.class, () -> read("index", null, "content", file, null, cursor));
        assertThrows(IOException.class, () -> read("index", null, "content", file, null, null));
        assertThrows(IOException.class, () -> read("disk", null, "manifest", null, null, cursor));
        assertFalse(read("disk", "repository", "manifest", null, null, null).get("complete").getAsBoolean());
        assertEquals(bytes.length - 1, read("head", null, "literalSearch", null, "*", null)
            .getAsJsonArray("entries").get(0).getAsJsonObject().get("byteOffset").getAsInt());
    }

    @Test
    public void revocationAndSubstitutionAreCheckedAgainstTheCapturedRootAndBranch() throws IOException
    {
        var review = new ReviewScope("repo", root.toString(), Set.of(project.branch()));
        var grant = new Grant(project.name(), "location", Set.of("bsl"), false, Set.of(), false, review);
        ProjectSnapshotService.requireReviewGrant(List.of(grant), "repo", project);
        var revoked = new Grant(project.name(), "location", Set.of("bsl"));
        assertThrows(IOException.class, () -> ProjectSnapshotService.requireReviewGrant(List.of(revoked), "repo", project));
        assertThrows(IOException.class, () -> ProjectSnapshotService.requireReviewGrant(List.of(grant), "other", project));
        assertThrows(IOException.class, () -> review.require("repo", Path.of("other"), project.branch()));
        assertThrows(IOException.class, () -> review.require("repo", root, "refs/heads/other"));
    }

    @Test public void candidateDiffUsesParentBytesAndIncludesDeletedPaths() throws Exception
    {
        var old = new Version("before".getBytes());
        var fresh = new Version("after".getBytes());
        var rows = List.of(new Entry("src/A.bsl",old,old,fresh),new Entry("src/B.bsl",old,old,null),
            new Entry("src/C.bsl",null,null,fresh),new Entry("src/Unchanged.bsl",old,old,old),
            new Entry("src/Foreign.bsl",old,old,fresh));
        var captured = new Project("Base","refs/heads/main","parent",rows,false);
        var snapshot = new ProjectSnapshotCapture.Snapshot(List.of(captured),java.util.Map.of(),ProjectSnapshotCapture.ModelStatus.UNKNOWN);
        var delta = ProjectSnapshotService.candidateDiff(snapshot,"Base",Set.of("src/A.bsl","src/B.bsl","src/C.bsl"));
        String cursor = null;
        var compared = new java.util.ArrayList<JsonObject>();
        do
        {
            var query = new Query("repo","Base",version,"candidate",null,"diff",null,null,cursor);
            var result = JsonParser.parseString(ProjectVersionReader.read(cursors,query,
                new Project("Base",captured.branch(),captured.head(),delta,false)).toJson()).getAsJsonObject();
            result.getAsJsonArray("entries").forEach(row -> compared.add(row.getAsJsonObject()));
            cursor = result.has("nextCursor") ? result.get("nextCursor").getAsString() : null;
        } while (cursor != null);
        assertEquals(List.of("src/A.bsl","src/B.bsl","src/C.bsl"),compared.stream().map(row -> row.get("path").getAsString()).toList());
        assertEquals(old.contentId(),compared.get(0).get("beforeId").getAsString());
        assertEquals(fresh.contentId(),compared.get(0).get("afterId").getAsString());
        assertFalse(compared.get(1).has("afterId"));
        assertFalse(compared.get(2).has("beforeId"));
    }

}
