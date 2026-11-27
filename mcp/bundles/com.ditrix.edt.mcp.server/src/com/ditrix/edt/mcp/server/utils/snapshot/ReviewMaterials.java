/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils.snapshot;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.ditrix.edt.mcp.server.utils.snapshot.ProjectSnapshotCapture.Snapshot;
import com.ditrix.edt.mcp.server.utils.snapshot.ReviewEvidenceStore.Cycle;
import com.ditrix.edt.mcp.server.utils.snapshot.ReviewEvidenceStore.File;

/** Captures the typed manifest used both for retention and for subsequent three-way grouping. */
final class ReviewMaterials
{
    final List<File> files = new ArrayList<>();
    final Map<String,byte[]> payload = new HashMap<>();

    void sources(Snapshot snapshot,List<String> tests) throws IOException
    {
        if (tests == null || !snapshot.projects().stream().map(ProjectSourceManifest.Project::name).toList().containsAll(tests))
        {
            throw new IOException("Identify the test projects in the admitted build.");
        }
        for (var project : snapshot.projects())
        {
            String group = tests.contains(project.name()) ? "tests" : "production";
            for (var entry : project.entries())
            {
                if (entry.disk() != null) { put(group,project.name(),entry.path(),entry.disk().bytes()); }
            }
            for (var entry : project.buildInputs().files().entrySet())
            {
                put(group,project.name(),entry.getKey(),entry.getValue().bytes());
            }
        }
    }

    void documents(Cycle cycle,String sha,Map<String,String> expected) throws IOException
    {
        var q = new ChangeArtifacts.Query(cycle.repoId(),cycle.projectName(),cycle.branch(),cycle.changeId(),sha);
        var listing = com.google.gson.JsonParser.parseString(ChangeArtifacts.read(q,"list",null,null).toJson()).getAsJsonObject();
        Set<String> seen = new HashSet<>();
        int total = 0;
        for (var item : listing.getAsJsonArray("artifacts"))
        {
            var r = com.ditrix.edt.mcp.server.protocol.GsonProvider.fromJson(item.toString(),ChangeArtifacts.Revision.class);
            if (!Set.of("proposal","design","tasks","delta").contains(r.kind())) { continue; }
            var key = new ChangeArtifacts.Key(r.kind(),r.itemId());
            String label = key.itemId() == null ? key.kind() : key.kind() + "/" + key.itemId();
            seen.add(label);
            if (expected != null && !r.revision().equals(expected.get(label))) { throw new ChangeArtifactWriter.Conflict(); }
            var raw = com.google.gson.JsonParser.parseString(ChangeArtifacts.read(q,"read",key,r.revision()).toJson()).getAsJsonObject();
            byte[] bytes = java.util.Base64.getDecoder().decode(raw.get("base64").getAsString());
            if ((total += bytes.length) > 4 * 1024 * 1024) { throw new IOException("Normative documents exceed retention limit."); }
            put("documents",q.projectName(),key.relative(),bytes);
        }
        if (!seen.containsAll(Set.of("design","tasks","proposal")) || expected != null && !seen.equals(expected.keySet()))
        {
            throw new IOException("Incomplete normative document selection.");
        }
    }

    private void put(String group,String project,String path,byte[] bytes)
    {
        String digest = ProjectSourceManifest.contentIdOf(bytes);
        files.add(new File(group,project,path,digest,bytes.length));
        payload.putIfAbsent(digest,bytes);
    }
}
