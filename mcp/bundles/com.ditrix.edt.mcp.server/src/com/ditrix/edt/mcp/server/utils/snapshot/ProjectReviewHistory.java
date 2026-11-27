/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils.snapshot;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevSort;
import org.eclipse.jgit.revwalk.RevWalk;

import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.utils.snapshot.SnapshotSourcePolicy.Access;

/** Resolves a bounded commit graph rooted at the exact authorized ref. */
public final class ProjectReviewHistory
{
    public static final int MAX_COMMITS = 4096;
    private static final int MAX_PHASES = 128;

    private ProjectReviewHistory() { }

    record Phase(String sha, String treeId, List<String> parents, String phase) { }
    record Range(String fromSha, String toSha) { }

    public static ToolResult resolve(String repoId, String project, String branch, String change, String expected)
        throws IOException
    {
        if (change == null || !change.matches("[a-z0-9][a-z0-9-]{0,127}")
            || expected != null && !ObjectId.isId(expected))
        {
            throw new IOException("Expected a change ID and an optional full commit SHA.");
        }
        return withRepository(repoId, project, branch, (repo, scope) -> resolve(repo, scope.grant().review(), branch, change, expected))
            .put("repoId", repoId).put("projectName", project).put("branch", branch).put("changeId", change);
    }

    @FunctionalInterface
    interface ScopedRead<T>
    {
        T read(Repository repository, SnapshotSourcePolicy.ScopedProject project) throws IOException;
    }

    static <T> T withRepository(String repoId, String project, String branch,
        ScopedRead<T> reader) throws IOException
    {
        Access access = SnapshotSourcePolicy.require(project, List.of());
        ReviewScope grant = requireScope(access, repoId, branch);
        Path root = Path.of(grant.workTree());
        T result;
        try
        {
            // A nested working tree has a different history even when its directory is under this grant.
            for (Path dir = access.projects().get(0).location(); !dir.equals(root); dir = dir.getParent())
            {
                try (var child = access.tree().openProject(dir.toString()); var nested = child.childIfPresent(".git"))
                {
                    if (nested != null)
                    {
                        throw SnapshotSourcePolicy.denied();
                    }
                }
            }
            result = ProjectSourceManifest.readRepository(access.tree(), root,
                repo -> reader.read(repo, access.projects().get(0)));
        }
        catch (ChangeArtifactWriter.Conflict e)
        {
            throw e;
        }
        catch (IOException | RuntimeException e)
        {
            throw new IOException("Authorized repository data is unavailable.");
        }
        Access current = SnapshotSourcePolicy.require(project, List.of());
        SnapshotSourcePolicy.requireGrantScope(List.of(access.projects().get(0).grant()),
            List.of(current.projects().get(0).grant()), false);
        if (!grant.equals(requireScope(current, repoId, branch)))
        {
            throw SnapshotSourcePolicy.denied();
        }
        return result;
    }

    private static ReviewScope requireScope(Access access, String repoId, String branch) throws IOException
    {
        var project = access.projects().get(0);
        var grant = project.grant().review();
        if (grant == null || !grant.repoId().equals(repoId) || !grant.branches().contains(branch)
            || !project.location().startsWith(Path.of(grant.workTree())))
        {
            throw SnapshotSourcePolicy.denied();
        }
        return grant;
    }

    static ToolResult resolve(Repository repo, ReviewScope scope, String branch, String change, String expected) throws IOException
    {
        ChangeArtifacts.require(scope, change, "read");
        return resolve(repo, branch, change, expected);
    }

    static ToolResult resolve(Repository repo, String branch, String change, String expected) throws IOException
    {
        var ref = repo.exactRef(branch);
        if (ref == null || ref.isSymbolic() || ref.getObjectId() == null)
        {
            return incomplete(ToolResult.success(), "branch_unavailable");
        }
        var response = ToolResult.success().put("observedHead", ref.getObjectId().name());
        if (!repo.getObjectDatabase().getShallowCommits().isEmpty())
        {
            return incomplete(response, "shallow_history");
        }
        Pattern subject = Pattern.compile("^(?:\\[[^\\]\\s]+\\]|[^:\\s]+:) (dev-build|design) "
            + Pattern.quote(change) + "$");
        List<Phase> phases = new ArrayList<>();
        List<RevCommit> builds = new ArrayList<>();
        Map<ObjectId, RevCommit> graph = new HashMap<>();
        try (var walk = new RevWalk(repo))
        {
            walk.sort(RevSort.TOPO);
            walk.markStart(walk.parseCommit(ref.getObjectId()));
            for (RevCommit commit : walk)
            {
                if (graph.size() == MAX_COMMITS)
                {
                    return incomplete(response, "commit_limit");
                }
                graph.put(commit, commit);
                var match = subject.matcher(commit.getShortMessage());
                if (match.matches())
                {
                    if (phases.size() == MAX_PHASES)
                    {
                        return incomplete(response, "phase_limit");
                    }
                    phases.add(new Phase(commit.name(), commit.getTree().name(),
                        Arrays.stream(commit.getParents()).map(RevCommit::name).toList(), match.group(1)));
                    if (match.group(1).equals("dev-build"))
                    {
                        builds.add(commit);
                    }
                }
            }
        }
        response.put("phases", phases);
        if (builds.isEmpty())
        {
            return incomplete(response, "build_missing");
        }
        List<Range> deltas = new ArrayList<>();
        for (int i = builds.size() - 1; i >= 0; i--)
        {
            RevCommit build = builds.get(i);
            if (build.getParentCount() != 1 || i + 1 < builds.size() && !ancestor(graph, builds.get(i + 1), build))
            {
                return incomplete(response, "ambiguous_build_history");
            }
            deltas.add(new Range(build.getParent(0).name(), build.name()));
        }
        String base = deltas.get(0).fromSha();
        String last = builds.get(0).name();
        if (expected != null && !last.equalsIgnoreCase(expected))
        {
            return incomplete(response, "expected_final_mismatch");
        }
        return response.put("complete", true).put("baseSha", base).put("finalSha", last)
            .put("range", new Range(base, last)).put("buildDeltas", deltas);
    }

    private static boolean ancestor(Map<ObjectId, RevCommit> graph, RevCommit wanted, RevCommit tip)
    {
        var queue = new ArrayDeque<RevCommit>();
        Set<ObjectId> seen = new HashSet<>();
        queue.push(tip);
        while (!queue.isEmpty())
        {
            RevCommit c = queue.pop();
            if (seen.add(c))
            {
                if (c.equals(wanted))
                {
                    return true;
                }
                for (var p : graph.get(c).getParents())
                {
                    queue.push(p);
                }
            }
        }
        return false;
    }

    private static ToolResult incomplete(ToolResult response, String reason)
    {
        return response.put("complete", false).put("reason", reason);
    }
}
