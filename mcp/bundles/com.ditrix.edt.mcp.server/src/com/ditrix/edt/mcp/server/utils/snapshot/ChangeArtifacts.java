/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils.snapshot;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;

import com.ditrix.edt.mcp.server.protocol.GsonProvider;
import com.ditrix.edt.mcp.server.protocol.ToolResult;

public final class ChangeArtifacts
{
    public static final int MAX_BYTES = 256 * 1024;
    static final int MAX_ITEMS = 128;
    private static final Map<String, String> FIXED = Map.ofEntries(Map.entry("proposal", "proposal.md"),
        Map.entry("design", "design.md"), Map.entry("tasks", "tasks.md"), Map.entry("designReview", "design-review.md"),
        Map.entry("buildReview", "dev-build-review.md"), Map.entry("iterationLog", "iteration-log.md"),
        Map.entry("metadataPlan", "metadata-plan.md"), Map.entry("metadataReview", "metadata-review.md"),
        Map.entry("prepassExclusions", "bsl-prepass-exclusions.txt"));
    private static final Map<String,String> EVIDENCE = Map.of("boundary","metadata-boundary-", "raw","metadata-raw-",
        "manifest","metadata-manifest-", "reviewBody","metadata-review.candidate-");

    private ChangeArtifacts() { }

    public record Query(String repoId, String projectName, String branch, String changeId, String commitSha) { }
    public record Key(String kind, String itemId)
    {
        String relative() throws IOException
        {
            if (FIXED.containsKey(kind) && itemId == null) { return FIXED.get(kind); }
            if (!validId(itemId)) { throw SnapshotSourcePolicy.denied(); }
            if ("delta".equals(kind)) { return "specs/" + itemId + "/spec.md"; }
            if (EVIDENCE.containsKey(kind))
            {
                return EVIDENCE.get(kind) + itemId + ("reviewBody".equals(kind) ? ".md" : ".json");
            }
            throw SnapshotSourcePolicy.denied();
        }
    }

    static boolean validId(String value)
    {
        return value != null && value.matches("[a-z0-9][a-z0-9-]{0,127}") && !"archive".equals(value)
            && !value.matches("(?:con|prn|aux|nul|com[1-9]|lpt[1-9])");
    }

    static void require(ReviewScope grant, String change, String permission) throws IOException
    {
        if (!validId(change) || grant.changes() == null || !grant.changes().getOrDefault(change, Set.of()).contains(permission))
        {
            throw SnapshotSourcePolicy.denied();
        }
    }

    static String prefix(String change) throws IOException
    {
        if (!validId(change)) { throw SnapshotSourcePolicy.denied(); }
        return "openspec/changes/" + change + "/";
    }

    record Revision(String kind, String itemId, String revision, String gitBlobId, String digest, int bytes) { }

    static Revision revision(Query q, Key key, byte[] bytes) throws IOException
    {
        String blob;
        try (var hash = new ObjectInserter.Formatter())
        {
            blob = hash.idFor(Constants.OBJ_BLOB, bytes).name();
        }
        String digest = ProjectSourceManifest.contentIdOf(bytes);
        String revision = ProjectSourceManifest.contentIdOf(GsonProvider.toJson(List.of(q.repoId(), q.projectName(), q.branch(),
            q.changeId(), key.relative(), q.commitSha() == null ? "workingTree" : q.commitSha(), digest))
            .getBytes(StandardCharsets.UTF_8));
        return new Revision(key.kind(), key.itemId(), revision, blob, digest, bytes.length);
    }

    public static ToolResult read(Query q, String view, Key requested, String expected) throws IOException
    {
        if (!validId(q.changeId()) || !Set.of("list", "read").contains(view)
            || "read".equals(view) != (requested != null) || requested == null && expected != null
            || q.commitSha() != null && !ObjectId.isId(q.commitSha()))
        {
            throw new IOException("Invalid change artifact request.");
        }
        if (requested != null) { requested.relative(); }
        return ProjectReviewHistory.withRepository(q.repoId(), q.projectName(), q.branch(), (repo, scoped) -> {
            require(scoped.grant().review(), q.changeId(), "read");
            String prefix = prefix(q.changeId());
            if (q.commitSha() == null)
            {
                if (!q.branch().equals(repo.getFullBranch())) { throw SnapshotSourcePolicy.denied(); }
                var tree = WindowsSourceTree.load();
                Path change = repo.getWorkTree().toPath().resolve(prefix);
                var disk = new Disk(tree,repo,change);
                try (var directory = disk.open(change,false,false))
                {
                    if (directory.readIfPresent("proposal.md", MAX_BYTES) == null) { throw SnapshotSourcePolicy.denied(); }
                    return result(q, requested, expected, disk);
                }
            }
            try (var walk = new RevWalk(repo))
            {
                var ref = repo.exactRef(q.branch());
                if (ref == null || ref.isSymbolic() || ref.getObjectId() == null) { throw SnapshotSourcePolicy.denied(); }
                walk.markStart(walk.parseCommit(ref.getObjectId()));
                int count = 0;
                for (var c : walk)
                {
                    if (++count > ProjectReviewHistory.MAX_COMMITS) { throw SnapshotSourcePolicy.denied(); }
                    if (c.name().equalsIgnoreCase(q.commitSha()))
                    {
                        var source = new Committed(repo, c, prefix);
                        if (source.bytes("proposal.md") == null) { throw SnapshotSourcePolicy.denied(); }
                        return result(q, requested, expected, source);
                    }
                }
                throw SnapshotSourcePolicy.denied();
            }
        });
    }

    interface Source
    {
        byte[] bytes(String relative) throws IOException;
        List<String> folders(String relative) throws IOException;
        List<String> names() throws IOException;
    }

    record Disk(WindowsSourceTree tree, Repository repo, Path change) implements Source
    {
        WindowsSourceTree.Directory open(Path directory, boolean create, boolean optional) throws IOException
        {
            Path root = repo.getWorkTree().toPath();
            if (!directory.startsWith(change) || !directory.startsWith(root)) { throw SnapshotSourcePolicy.denied(); }
            var index = repo.readDirCache();
            var headTree = repo.resolve("HEAD^{tree}");
            var parts = root.relativize(directory);
            try (var walk = new TreeWalk(repo))
            {
                walk.addTree(new org.eclipse.jgit.dircache.DirCacheIterator(index));
                if (headTree != null) { walk.addTree(headTree); }
                while (walk.next())
                {
                    int depth = walk.getDepth();
                    if (!walk.getNameString().equalsIgnoreCase(parts.getName(depth).toString())) { continue; }
                    for (int i = 0; i < walk.getTreeCount(); i++)
                    {
                        if (walk.getFileMode(i) == FileMode.GITLINK) { throw SnapshotSourcePolicy.denied(); }
                    }
                    if (walk.isSubtree() && depth + 1 < parts.getNameCount()) { walk.enterSubtree(); }
                }
            }
            return tree.openRepositoryDirectory(root, directory, create, optional);
        }

        public List<String> names() throws IOException
        {
            try (var dir = open(change, false, false))
            {
                return dir.entries(MAX_ITEMS * 4).stream().filter(e -> !e.directory()).map(WindowsSourceTree.Entry::name).toList();
            }
        }
        public byte[] bytes(String relative) throws IOException
        {
            Path file = change.resolve(relative);
            try (var parent = open(file.getParent(), false, true))
            {
                return parent == null ? null : parent.readIfPresent(file.getFileName().toString(), MAX_BYTES);
            }
        }

        public List<String> folders(String relative) throws IOException
        {
            try (var dir = open(change.resolve(relative), false, true))
            {
                if (dir == null) { return List.of(); }
                var result = new ArrayList<String>();
                for (var entry : dir.entries(MAX_ITEMS))
                {
                    if (entry.reparsePoint()) { throw SnapshotSourcePolicy.denied(); }
                    if (entry.directory() && validId(entry.name())) { result.add(entry.name()); }
                }
                return result.stream().sorted().toList();
            }
        }
    }

    record Committed(Repository repo, RevCommit commit, String prefix) implements Source
    {
        public List<String> names() throws IOException
        {
            try (var path = TreeWalk.forPath(repo,prefix.substring(0,prefix.length()-1),commit.getTree());
                var items = new TreeWalk(repo))
            {
                if (path == null || !path.isSubtree()) { throw SnapshotSourcePolicy.denied(); }
                items.addTree(path.getObjectId(0));
                var names = new ArrayList<String>();
                while (items.next())
                {
                    if (names.size() >= MAX_ITEMS * 4) { throw SnapshotSourcePolicy.denied(); }
                    names.add(items.getNameString());
                }
                return names;
            }
        }
        public byte[] bytes(String relative) throws IOException
        {
            try (var entry = TreeWalk.forPath(repo, prefix + relative, commit.getTree()))
            {
                if (entry == null) { return null; }
                if (entry.getFileMode(0) != FileMode.REGULAR_FILE) { throw SnapshotSourcePolicy.denied(); }
                return repo.open(entry.getObjectId(0), Constants.OBJ_BLOB).getCachedBytes(MAX_BYTES);
            }
        }

        public List<String> folders(String relative) throws IOException
        {
            try (var entry = TreeWalk.forPath(repo, prefix + relative, commit.getTree()))
            {
                if (entry == null) { return List.of(); }
                if (entry.getFileMode(0) != FileMode.TREE) { throw SnapshotSourcePolicy.denied(); }
                try (var children = new TreeWalk(repo))
                {
                    children.addTree(entry.getObjectId(0));
                    var result = new ArrayList<String>();
                    int visited = 0;
                    while (children.next())
                    {
                        if (++visited > MAX_ITEMS || children.getFileMode(0) == FileMode.SYMLINK) { throw SnapshotSourcePolicy.denied(); }
                        if (children.isSubtree() && validId(children.getNameString())) { result.add(children.getNameString()); }
                    }
                    return result;
                }
            }
        }
    }

    static ToolResult result(Query q, Key requested, String expected, Source source) throws IOException
    {
        var result = ToolResult.success().put("repoId", q.repoId()).put("changeId", q.changeId())
            .put("source", q.commitSha() == null ? "workingTree" : q.commitSha());
        if (requested != null)
        {
            byte[] bytes = source.bytes(requested.relative());
            if (bytes == null) { throw new IOException("Requested artifact is unavailable."); }
            var revision = revision(q, requested, bytes);
            if (expected != null && !expected.equals(revision.revision())) { throw new IOException("Artifact revision changed."); }
            return result.put("artifact", revision).put("base64", Base64.getEncoder().encodeToString(bytes));
        }
        List<Key> keys = new ArrayList<>();
        new TreeMap<>(FIXED).keySet().forEach(kind -> keys.add(new Key(kind, null)));
        for (var id : source.folders("specs")) { keys.add(new Key("delta", id)); }
        for (String name : source.names().stream().sorted().toList())
        {
            for (var type : EVIDENCE.entrySet())
            {
                String suffix = "reviewBody".equals(type.getKey()) ? ".md" : ".json";
                if (name.startsWith(type.getValue()) && name.endsWith(suffix))
                {
                    String id = name.substring(type.getValue().length(), name.length()-suffix.length());
                    if (validId(id)) { keys.add(new Key(type.getKey(), id)); }
                }
            }
        }
        List<Revision> revisions = new ArrayList<>();
        int bytesRead = 0;
        for (var key : keys)
        {
            byte[] bytes = source.bytes(key.relative());
            if (bytes != null)
            {
                bytesRead += bytes.length;
                if (revisions.size() >= MAX_ITEMS || bytesRead > 4 * 1024 * 1024)
                {
                    throw new IOException("Artifact list exceeds the package limit.");
                }
                revisions.add(revision(q, key, bytes));
            }
        }
        return result.put("artifacts", revisions);
    }
}
