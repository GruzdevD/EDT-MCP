/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils.snapshot;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevWalk;
import com.ditrix.edt.mcp.server.protocol.GsonProvider;
import com.ditrix.edt.mcp.server.utils.snapshot.ProjectCommitPlan.Selection;
import com.ditrix.edt.mcp.server.utils.snapshot.ProjectSourceManifest.Version;
import com.ditrix.edt.mcp.server.utils.snapshot.SnapshotSourcePolicy.Grant;
import com.google.gson.JsonParser;

/** Explicit inputs of a build preview; persisted coordinates contain neither credentials nor file bytes. */
public record BuildReview(String repoId, String changeId, String projectName, List<Artifact> artifacts,
    List<Dependency> dependencies)
{
    public record Artifact(String kind, String itemId, String revision) { }
    public record Dependency(String repoId, String projectName, String branch, String commitSha) { }

    public static BuildReview parse(String json) throws IOException
    {
        if (json == null) { return null; }
        if (json.length() > 32768) { throw new IOException("Build review input exceeds the request limit."); }
        BuildReview spec = GsonProvider.fromJson(json, BuildReview.class);
        if (spec == null || !ChangeArtifacts.validId(spec.changeId) || spec.repoId == null || spec.projectName == null
            || !WindowsSourceTree.isComponent(spec.projectName)) { throw SnapshotSourcePolicy.denied(); }
        var docs = spec.artifacts == null ? List.<Artifact>of() : List.copyOf(spec.artifacts);
        var deps = spec.dependencies == null ? List.<Dependency>of() : List.copyOf(spec.dependencies);
        if (docs.size() > 32 || deps.size() > 15) { throw new IOException("Too many build inputs."); }
        Set<String> paths = new HashSet<>();
        for (var doc : docs)
        {
            if (doc.revision() == null || !doc.revision().matches("[a-f0-9]{64}")
                || !paths.add(new ChangeArtifacts.Key(doc.kind(), doc.itemId()).relative()))
            {
                throw new IOException("Each selected artifact requires its unique path and exact revision.");
            }
        }
        Set<String> names = new HashSet<>(Set.of(spec.projectName));
        for (var d : deps)
        {
            if (d.repoId() == null || d.branch() == null || d.commitSha() == null || !org.eclipse.jgit.lib.ObjectId.isId(d.commitSha())
                || !WindowsSourceTree.isComponent(d.projectName()) || !names.add(d.projectName()))
            {
                throw new IOException("Pin each distinct additional project to its repository, branch and full SHA.");
            }
        }
        return new BuildReview(spec.repoId, spec.changeId, spec.projectName, docs, deps);
    }

    void require(List<Grant> grants, String branch, java.nio.file.Path workTree) throws IOException
    {
        var grant = grants.stream().filter(g -> projectName.equals(g.name())).findFirst().orElseThrow(SnapshotSourcePolicy::denied);
        if (grant.review() == null) { throw SnapshotSourcePolicy.denied(); }
        grant.review().require(repoId, workTree, branch);
        ChangeArtifacts.require(grant.review(), changeId, "read");
        if (!artifacts.isEmpty()) { ChangeArtifacts.require(grant.review(), changeId, "include"); }
    }

    Selection attach(Selection selection, Repository repo, WindowsSourceTree windows, UUID snapshotId,
        List<Grant> current, List<ProjectSourceManifest.Project> captured) throws IOException
    {
        require(current, selection.branch(), selection.workTree());
        var project = captured.get(0);
        if (!projectName.equals(project.name()) || !selection.workTree().equals(project.workTree())
            || !selection.branch().equals(project.branch()) || !selection.parent().name().equals(project.head()))
        { throw SnapshotSourcePolicy.denied(); }
        var grant = current.stream().filter(g -> projectName.equals(g.name())).findFirst().orElseThrow(SnapshotSourcePolicy::denied);
        String prefix = ChangeArtifacts.prefix(changeId);
        var disk = new ChangeArtifacts.Disk(windows, repo, selection.workTree().resolve(prefix));
        if (disk.bytes("proposal.md") == null) { throw SnapshotSourcePolicy.denied(); }
        var q = new ChangeArtifacts.Query(repoId, projectName, selection.branch(), changeId, null);
        var choices = new ArrayList<>(selection.entries());
        try (var walk = new RevWalk(repo))
        {
            var commit = walk.parseCommit(selection.parent());
            var old = new ChangeArtifacts.Committed(repo, commit, prefix);
            var index = repo.readDirCache();
            for (var doc : artifacts)
            {
                var key = new ChangeArtifacts.Key(doc.kind(), doc.itemId());
                String relative = key.relative();
                byte[] bytes = disk.bytes(relative);
                if (bytes == null || !doc.revision().equals(ChangeArtifacts.revision(q, key, bytes).revision()))
                {
                    throw ProjectCommitPlan.stale();
                }
                String path = prefix + relative;
                var staged = index.getEntry(path);
                if (staged != null && staged.getFileMode() != FileMode.REGULAR_FILE) { throw ProjectCommitPlan.stale(); }
                byte[] head = old.bytes(relative);
                Version indexVersion = staged == null ? null : new Version(repo.open(staged.getObjectId(), Constants.OBJ_BLOB)
                    .getCachedBytes(ChangeArtifacts.MAX_BYTES));
                var source = new ProjectSourceManifest.Entry(path, head == null ? null : new Version(head), indexVersion, new Version(bytes));
                var id = UUID.nameUUIDFromBytes((snapshotId + "/" + doc.revision()).getBytes(StandardCharsets.UTF_8));
                choices.add(new ProjectCommitPlan.SelectedEntry(id, project, grant, source, path, true));
            }
        }
        return new Selection(selection.workTree(), selection.branch(), selection.parent(), selection.modelStatus(),
            List.copyOf(choices), selection.renamePeers());
    }
}
