/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils.snapshot;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;
import com.ditrix.edt.mcp.server.protocol.GsonProvider;
import com.ditrix.edt.mcp.server.utils.snapshot.ProjectSnapshotCapture.Snapshot;
import com.ditrix.edt.mcp.server.utils.snapshot.ProjectSourceManifest.Entry;
import com.ditrix.edt.mcp.server.utils.snapshot.ProjectSourceManifest.Project;
import com.ditrix.edt.mcp.server.utils.snapshot.ProjectSourceManifest.Version;
import com.ditrix.edt.mcp.server.utils.snapshot.SnapshotSourcePolicy.Grant;
import com.ditrix.edt.mcp.server.utils.snapshot.SnapshotSourcePolicy.ScopedProject;

/** The same ordered set of exact source and descriptor bytes drives preview, reads and execution. */
final class GitBuildInputs
{
    private static final Set<String> DESCRIPTORS = Set.of(".project","DT-INF/PROJECT.PMF",".settings/org.eclipse.core.resources.prefs");
    private static final long MAX_BYTES = 64L * 1024 * 1024;
    record Input(String projectName, String repoId, String branch, String revision, String treeId, String fingerprint) { }
    record Contents(Snapshot snapshot, List<Grant> scope, List<Input> inputs, String manifestId, long bytes) { }

    private GitBuildInputs() { }

    static Contents candidate(Repository repo, ProjectCommitPlan.Plan plan, SnapshotSourcePolicy.Access access,
        BuildReview spec) throws IOException
    {
        List<Project> projects = new ArrayList<>();
        List<Grant> grants = new ArrayList<>();
        List<Input> inputs = new ArrayList<>();
        Budget budget = new Budget();
        Set<String> pinned = spec.dependencies().stream().map(BuildReview.Dependency::projectName)
            .collect(java.util.stream.Collectors.toSet());
        try (var walk = new RevWalk(repo))
        {
            var parent = walk.parseCommit(plan.selection().parent());
            for (var scoped : access.projects())
            {
                Grant g = scoped.grant();
                if (pinned.contains(g.name()))
                {
                    if (plan.changes().stream().anyMatch(c -> c.selected().grant().name().equals(g.name())))
                    {
                        throw new IOException("A read-only dependency cannot be selected for writing.");
                    }
                    continue;
                }
                if (!g.yaxunit() || g.review() == null) { throw SnapshotSourcePolicy.denied(); }
                g.review().require(spec.repoId(), plan.selection().workTree(),plan.selection().branch());
                var p = project(repo, scoped, parent, plan.selection().branch(), plan.changes(), budget);
                projects.add(p);
                grants.add(g);
                inputs.add(input(p,g,"candidate",plan.tree().name()));
            }
        }
        dependencies(spec.dependencies(), projects, grants, inputs, budget,true);
        return finish(projects,grants,inputs,budget);
    }

    static Contents committed(BuildReview.Dependency base, List<BuildReview.Dependency> extra) throws IOException
    {
        return committed(base,extra,true);
    }
    static Contents committedForRead(BuildReview.Dependency base,List<BuildReview.Dependency> extra) throws IOException
    {
        return committed(base,extra,false);
    }
    private static Contents committed(BuildReview.Dependency base,List<BuildReview.Dependency> extra,boolean execution) throws IOException
    {
        var all = new ArrayList<BuildReview.Dependency>();
        all.add(base);
        all.addAll(extra);
        var projects = new ArrayList<Project>();
        var grants = new ArrayList<Grant>();
        var inputs = new ArrayList<Input>();
        var budget = new Budget();
        dependencies(all,projects,grants,inputs,budget,execution);
        return finish(projects,grants,inputs,budget);
    }

    private static void dependencies(List<BuildReview.Dependency> deps, List<Project> projects, List<Grant> grants,
        List<Input> inputs, Budget budget, boolean execution) throws IOException
    {
        for (var d : deps)
        {
            ProjectReviewHistory.withRepository(d.repoId(),d.projectName(),d.branch(),(repo, scoped) -> {
                if ((execution && !scoped.grant().yaxunit()) || d.commitSha() == null || !ObjectId.isId(d.commitSha())) { throw SnapshotSourcePolicy.denied(); }
                try (var walk = new RevWalk(repo))
                {
                    var ref = repo.exactRef(d.branch());
                    if (ref == null || ref.isSymbolic() || ref.getObjectId() == null) { throw SnapshotSourcePolicy.denied(); }
                    walk.markStart(walk.parseCommit(ref.getObjectId()));
                    int count = 0;
                    for (var c : walk)
                    {
                        if (++count > ProjectReviewHistory.MAX_COMMITS) { break; }
                        if (c.name().equalsIgnoreCase(d.commitSha()))
                        {
                            Project p = project(repo, scoped,c,d.branch(),List.of(), budget);
                            projects.add(p);
                            grants.add(scoped.grant());
                            inputs.add(input(p,scoped.grant(),c.name(),c.getTree().name()));
                            return true;
                        }
                    }
                    throw SnapshotSourcePolicy.denied();
                }
            });
        }
    }

    private static Input input(Project project, Grant grant, String revision, String tree)
    {
        return new Input(project.name(),grant.review().repoId(),project.branch(),revision,tree,
            ProjectSnapshotCapture.fingerprints(List.of(project)).get(project.name()));
    }

    static Contents finish(List<Project> projects,List<Grant> grants,List<Input> inputs,Budget budget) throws IOException
    {
        if (projects.isEmpty() || projects.size() > 16
            || projects.stream().map(Project::name).distinct().count() != projects.size())
        {
            throw SnapshotBuildInputs.unsupported();
        }
        SnapshotBuildInputs.validateScope(projects);
        var snapshot = new Snapshot(projects, ProjectSnapshotCapture.fingerprints(projects), ProjectSnapshotCapture.ModelStatus.UNKNOWN);
        return new Contents(snapshot,List.copyOf(grants),List.copyOf(inputs),
            manifestId(inputs),budget.bytes);
    }

    static String manifestId(List<Input> inputs)
    {
        String identity = GsonProvider.toJson(inputs.stream().map(i -> List.of(i.projectName(),i.fingerprint())).toList());
        return ProjectSourceManifest.contentIdOf(identity.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    static final class Budget
    {
        int paths;
        long bytes;
        void add(long size) throws IOException
        {
            bytes += size;
            if (size > ProjectVersionReader.FILE_BYTES || bytes > MAX_BYTES || ++paths > ProjectSourceManifest.MAX_DIRECTORY_ENTRIES)
            {
                throw new IOException("Git build input limit exceeded.");
            }
        }
    }

    static Project project(Repository repo, ScopedProject scoped, RevCommit parent, String branch,
        List<ProjectCommitPlan.Change> changes, Budget budget) throws IOException
    {
        Path root = repo.getWorkTree().toPath();
        String prefix = root.relativize(scoped.location()).toString().replace('\\','/');
        if (!prefix.isEmpty()) { prefix += "/"; }
        String crlf = repo.getConfig().getString("core",null,"autocrlf");
        if (crlf != null && !"false".equalsIgnoreCase(crlf) || repo.getConfig().getString("core",null,"attributesfile") != null
            || !repo.getConfig().getSubsections("filter").isEmpty()
            || !java.nio.file.Files.notExists(repo.getDirectory().toPath().resolve("info/attributes")))
        {
            throw SnapshotBuildInputs.unsupported();
        }
        Map<String,Version> sources = new TreeMap<>();
        Map<String,Version> descriptors = new TreeMap<>();
        Map<String,String> casing = new HashMap<>();
        try (var reader = repo.newObjectReader(); var tree = new TreeWalk(reader))
        {
            tree.addTree(parent.getTree());
            tree.setRecursive(true);
            int scanned = 0;
            while (tree.next())
            {
                if (++scanned > ProjectSourceManifest.MAX_DIRECTORY_ENTRIES) { throw SnapshotBuildInputs.unsupported(); }
                String path = tree.getPathString();
                if (path.equals(".gitattributes") || path.endsWith("/.gitattributes") &&
                    (path.startsWith(prefix) || prefix.startsWith(path.substring(0,path.lastIndexOf('/')+1))))
                {
                    throw SnapshotBuildInputs.unsupported();
                }
                if (!path.startsWith(prefix)) { continue; }
                String relative = path.substring(prefix.length());
                boolean descriptor = DESCRIPTORS.contains(relative);
                if (!descriptor && relative.startsWith("DT-INF/")) { throw SnapshotBuildInputs.unsupported(); }
                if (!descriptor && (!relative.startsWith("src/") || relative.endsWith("/.gitignore"))) { continue; }
                if ((!descriptor && !scoped.permits(relative)) || scoped.isWorkspaceLink(relative,false)
                    || tree.getFileMode(0) != FileMode.REGULAR_FILE && tree.getFileMode(0) != FileMode.EXECUTABLE_FILE)
                {
                    throw SnapshotBuildInputs.unsupported();
                }
                safePath(relative,casing);
                var loader = reader.open(tree.getObjectId(0),Constants.OBJ_BLOB);
                budget.add(loader.getSize());
                (descriptor ? descriptors : sources).put(relative,new Version(loader.getCachedBytes(ProjectVersionReader.FILE_BYTES)));
            }
        }
        for (var change : changes)
        {
            var choice = change.selected();
            if (choice.artifact() || !choice.project().name().equals(scoped.grant().name())) { continue; }
            var value = choice.source().disk();
            if (value == null) { sources.remove(choice.source().path()); }
            else
            {
                budget.add(value.size());
                sources.put(choice.source().path(),value);
            }
        }
        casing.clear();
        for (var path : sources.keySet()) { safePath(path,casing); }
        List<Entry> entries = sources.entrySet().stream().map(e -> new Entry(e.getKey(),null,null,e.getValue())).toList();
        return new Project(scoped.grant().name(),branch,parent.name(),entries,false,
            SnapshotBuildInputs.validate(scoped.grant().name(),descriptors),root);
    }

    private static void safePath(String path, Map<String,String> casing) throws IOException
    {
        StringBuilder prefix = new StringBuilder();
        for (String part : path.split("/",-1))
        {
            if (!ProjectSourceManifest.safeBuildComponent(part)) { throw SnapshotBuildInputs.unsupported(); }
            prefix.append('/').append(part);
            String full = prefix.toString();
            String old = casing.putIfAbsent(full.toLowerCase(Locale.ROOT),full);
            if (old != null && !old.equals(full)) { throw SnapshotBuildInputs.unsupported(); }
        }
    }
}
