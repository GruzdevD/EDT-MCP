/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils.snapshot;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.treewalk.filter.PathFilter;

import com.ditrix.edt.mcp.server.protocol.GsonProvider;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.utils.snapshot.ProjectSourceManifest.Entry;
import com.ditrix.edt.mcp.server.utils.snapshot.ProjectSourceManifest.Project;
import com.ditrix.edt.mcp.server.utils.snapshot.ProjectSourceManifest.Version;
import com.ditrix.edt.mcp.server.utils.snapshot.SnapshotSourcePolicy.ScopedProject;

final class ProjectCommitVersion
{
    private ProjectCommitVersion() { }

    static ToolResult inspect(ProjectSnapshotPages cursors, ProjectVersionReader.Query q) throws IOException
    {
        if (q.snapshotId() != null || !ObjectId.isId(q.commitSha()) || q.branch() == null || !"commit".equals(q.side())
            || q.baseSha() != null && (!"diff".equals(q.view()) || !ObjectId.isId(q.baseSha())))
        {
            throw new IOException("Expected a full commit SHA and authorized branch; baseSha is for diff only.");
        }
        return ProjectReviewHistory.withRepository(q.repoId(), q.projectName(), q.branch(), (repo, project) -> {
            var ref = repo.exactRef(q.branch());
            if (ref == null || ref.isSymbolic() || ref.getObjectId() == null)
            {
                return incomplete(q, "branch_unavailable");
            }
            try (var walk = new RevWalk(repo))
            {
                walk.markStart(walk.parseCommit(ref.getObjectId()));
                RevCommit target = null;
                RevCommit base = null;
                int count = 0;
                for (var c : walk)
                {
                    if (++count > ProjectReviewHistory.MAX_COMMITS)
                    {
                        return incomplete(q, "commit_limit");
                    }
                    if (c.name().equalsIgnoreCase(q.commitSha())) { target = c; }
                    if (c.name().equalsIgnoreCase(q.baseSha())) { base = c; }
                    if (target != null && (q.baseSha() == null || base != null)) { break; }
                }
                if (target == null || q.baseSha() != null && base == null)
                {
                    return incomplete(q, "unreachable");
                }
                if ("diff".equals(q.view()) && base == null && target.getParentCount() != 0)
                {
                    if (target.getParentCount() > 1) { return incomplete(q, "base_required"); }
                    base = walk.parseCommit(target.getParent(0));
                }
                String binding = GsonProvider.toJson(List.of(project.grant().location(),
                    project.grant().extensions().stream().sorted().toList(), project.grant().review()));
                Project captured = "perimeter".equals(q.scope()) ? readPerimeter(repo,project,q.branch(),target,base)
                    : readTrees(repo, project, q.branch(), target, base);
                ToolResult result = ProjectVersionReader.read(cursors, q, captured, binding).put("treeId", target.getTree().name());
                if (base != null) { result.put("baseSha", base.name()); }
                return result;
            }
            catch (LimitExceeded e)
            {
                return incomplete(q, e.getMessage());
            }
        });
    }

    private static ToolResult incomplete(ProjectVersionReader.Query q, String reason)
    {
        return ToolResult.success().put("commitSha", q.commitSha()).put("branch", q.branch())
            .put("repoId", q.repoId()).put("projectName", q.projectName()).put("complete", false).put("reason", reason);
    }

    static Project readPerimeter(Repository repo,ScopedProject project,String branch,RevCommit target,RevCommit base) throws IOException
    {
        var rows = new ArrayList<Entry>();
        long bytes = 0;
        for (String path : project.grant().review().perimeterPaths().stream().sorted().toList())
        {
            var sides = new Version[2];
            RevCommit[] commits = {target,base};
            for (int i = 0; i < commits.length; i++)
            {
                if (commits[i] == null) { continue; }
                try (var walk = TreeWalk.forPath(repo,path,commits[i].getTree()))
                {
                    if (walk == null) { continue; }
                    if (walk.getFileMode(0) != FileMode.REGULAR_FILE && walk.getFileMode(0) != FileMode.EXECUTABLE_FILE)
                    { throw new LimitExceeded("unsupported_entry"); }
                    var blob = repo.open(walk.getObjectId(0),Constants.OBJ_BLOB);
                    bytes += blob.getSize();
                    if (bytes > 64L*1024*1024 || blob.getSize() > ProjectVersionReader.FILE_BYTES)
                    { throw new LimitExceeded("source_byte_limit"); }
                    sides[i] = new Version(blob.getCachedBytes(ProjectVersionReader.FILE_BYTES));
                }
            }
            rows.add(new Entry(path,sides[1],null,sides[0]));
        }
        return new Project(project.grant().name(),branch,target.name(),List.of(),false,null,repo.getWorkTree().toPath(),rows);
    }

    static Project readTrees(Repository repo, ScopedProject project, String branch, RevCommit target, RevCommit base)
        throws IOException
    {
        Path root = repo.getWorkTree().toPath();
        Path location = project.location();
        String relative = root.relativize(location).toString().replace('\\', '/');
        String prefix = relative.isEmpty() ? "" : relative + "/";
        List<Entry> entries = new ArrayList<>();
        int visited = 0;
        long bytes = 0;
        try (var reader = repo.newObjectReader(); var trees = new TreeWalk(reader))
        {
            trees.addTree(target.getTree());
            if (base != null) { trees.addTree(base.getTree()); }
            trees.setRecursive(true);
            trees.setFilter(PathFilter.create(prefix + "src"));
            while (trees.next())
            {
                if (++visited > ProjectSourceManifest.MAX_DIRECTORY_ENTRIES) { throw new LimitExceeded("entry_limit"); }
                String path = project.grant().projectPathInRepository(prefix, trees.getPathString());
                if (path == null || !project.permits(path)) { continue; }
                Version[] sides = new Version[trees.getTreeCount()];
                for (int side = 0; side < sides.length; side++)
                {
                    var mode = trees.getFileMode(side);
                    if (mode == FileMode.MISSING) { continue; }
                    if (mode != FileMode.REGULAR_FILE && mode != FileMode.EXECUTABLE_FILE)
                    {
                        throw new LimitExceeded("unsupported_entry");
                    }
                    if (side == 1 && trees.idEqual(0, 1)) { sides[1] = sides[0]; continue; }
                    var blob = reader.open(trees.getObjectId(side), Constants.OBJ_BLOB);
                    bytes += blob.getSize();
                    if (blob.getSize() > ProjectVersionReader.FILE_BYTES || bytes > 64L * 1024 * 1024)
                    {
                        throw new LimitExceeded("source_byte_limit");
                    }
                    sides[side] = new Version(blob.getCachedBytes(ProjectVersionReader.FILE_BYTES));
                }
                entries.add(new Entry(path, base == null ? null : sides[1], null, sides[0]));
            }
        }
        return new Project(project.grant().name(), branch, target.name(), entries, false, null, root);
    }

    private static final class LimitExceeded extends IOException
    {
        private static final long serialVersionUID = 1L;
        LimitExceeded(String reason) { super(reason); }
    }
}
