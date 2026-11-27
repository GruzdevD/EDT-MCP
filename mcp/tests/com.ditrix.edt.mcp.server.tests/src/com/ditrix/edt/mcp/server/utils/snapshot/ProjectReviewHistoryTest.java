/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils.snapshot;

import static org.junit.Assert.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class ProjectReviewHistoryTest
{
    @Rule public TemporaryFolder fixture = new TemporaryFolder();

    private String git(String... args) throws Exception
    {
        var cmd = new ArrayList<>(List.of("git", "-c", "user.name=Fixture", "-c", "user.email=test@example.invalid",
            "-c", "commit.gpgSign=false", "-c", "core.hooksPath=unused-hooks"));
        cmd.addAll(List.of(args));
        var process = new ProcessBuilder(cmd).directory(fixture.getRoot()).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        assertEquals(output, 0, process.waitFor());
        return output;
    }

    private String commit(String subject) throws Exception
    {
        git("commit", "--allow-empty", "-m", subject);
        return git("rev-parse", "HEAD");
    }

    private JsonObject result() throws Exception
    {
        try (var repo = new FileRepositoryBuilder().setGitDir(fixture.getRoot().toPath().resolve(".git").toFile()).build())
        {
            return JsonParser.parseString(ProjectReviewHistory.resolve(repo, "refs/heads/main", "fix-rate", null)
                .toJson()).getAsJsonObject();
        }
    }

    @Test
    public void rangeIncludesMergeButBuildDeltasUseTheirOwnParents() throws Exception
    {
        git("init", "-b", "main");
        String base = commit("[T] dev-build fix-rate-other");
        git("branch", "upstream");
        String first = commit("T: dev-build fix-rate");
        git("checkout", "upstream");
        commit("upstream");
        git("checkout", "main");
        git("merge", "--no-ff", "-m", "integrate", "upstream");
        String last = commit("[T] dev-build fix-rate");
        String head = commit("[T] design fix-rate");
        var review = result();
        assertTrue(review.get("complete").getAsBoolean());
        assertEquals(head, review.get("observedHead").getAsString());
        assertEquals(base, review.get("baseSha").getAsString());
        assertEquals(last, review.get("finalSha").getAsString());
        var deltas = review.getAsJsonArray("buildDeltas");
        assertEquals(2, deltas.size());
        for (int i = 0; i < 2; i++)
        {
            String build = i == 0 ? first : last;
            assertEquals(build, deltas.get(i).getAsJsonObject().get("toSha").getAsString());
            assertEquals(git("rev-parse", build + "^"), deltas.get(i).getAsJsonObject().get("fromSha").getAsString());
        }
        var phases = review.getAsJsonArray("phases");
        assertEquals(3, phases.size());
        assertEquals("design", phases.get(0).getAsJsonObject().get("phase").getAsString());
        assertEquals(git("rev-parse", head + "^{tree}"), phases.get(0).getAsJsonObject().get("treeId").getAsString());
    }

    @Test
    public void readsRenameDeletionAndCallerOnlyFromTheRequestedGitTrees() throws Exception
    {
        var root = fixture.getRoot().toPath();
        var dir = Files.createDirectories(root.resolve("src"));
        git("init", "-b", "main");
        for (String file : List.of("Old.mdo", "Delete.bsl", "Caller.bsl"))
        {
            Files.writeString(dir.resolve(file), "unchanged " + file);
        }
        git("add", "src");
        String base = commit("base");
        git("mv", "src/Old.mdo", "src/New.mdo");
        git("rm", "src/Delete.bsl");
        String sha = commit("[T] dev-build fix-rate");
        Files.writeString(dir.resolve("Caller.bsl"), "changed in live tree");
        var grant = new SnapshotSourcePolicy.Grant("Base", root.toString(), java.util.Set.of("mdo", "bsl"));
        var scope = new SnapshotSourcePolicy.ScopedProject(org.mockito.Mockito.mock(
            org.eclipse.core.resources.IProject.class, org.mockito.Mockito.RETURNS_DEEP_STUBS), grant, root);
        try (var repo = new FileRepositoryBuilder().setGitDir(root.resolve(".git").toFile()).build();
            var walk = new org.eclipse.jgit.revwalk.RevWalk(repo))
        {
            var tree = ProjectCommitVersion.readTrees(repo, scope, "refs/heads/main", walk.parseCommit(repo.resolve(sha)),
                walk.parseCommit(repo.resolve(base)));
            var entries = tree.entries().stream().collect(java.util.stream.Collectors.toMap(
                ProjectSourceManifest.Entry::path, java.util.function.Function.identity()));
            assertNull(entries.get("src/Old.mdo").disk());
            assertNull(entries.get("src/Delete.bsl").disk());
            assertNull(entries.get("src/New.mdo").head());
            assertArrayEquals(entries.get("src/Old.mdo").head().bytes(), entries.get("src/New.mdo").disk().bytes());
            assertEquals(git("show", sha + ":src/Caller.bsl"),
                new String(entries.get("src/Caller.bsl").disk().bytes(), StandardCharsets.UTF_8));
        }
    }

    @Test
    public void incomparableBuildsDoNotGetABase() throws Exception
    {
        git("init", "-b", "main");
        commit("initial");
        git("branch", "other");
        commit("[T] dev-build fix-rate");
        git("checkout", "other");
        commit("T: dev-build fix-rate");
        git("checkout", "main");
        git("merge", "--no-ff", "-m", "merge", "other");
        var result = result();
        assertFalse(result.get("complete").getAsBoolean());
        assertEquals("ambiguous_build_history", result.get("reason").getAsString());
        assertFalse(result.has("baseSha"));
    }

    @Test public void revokingChangeReadRetainsBranchButDeniesHistory() throws Exception
    {
        git("init","-b","main");
        git("commit","--allow-empty","-m","[RV] design known");
        String branch = "refs/heads/main";
        var allowed = new ReviewScope("app",fixture.getRoot().toString(),java.util.Set.of(branch),java.util.Set.of(),
            java.util.Map.of("known",java.util.Set.of("read")));
        var revoked = new ReviewScope("app",fixture.getRoot().toString(),java.util.Set.of(branch));
        try (var repo = new FileRepositoryBuilder().setGitDir(fixture.getRoot().toPath().resolve(".git").toFile()).build())
        {
            ProjectReviewHistory.resolve(repo,allowed,branch,"known",null);
            assertThrows(java.io.IOException.class, () -> ProjectReviewHistory.resolve(repo,revoked,branch,"known",null));
            assertThrows(java.io.IOException.class, () -> ProjectReviewHistory.resolve(repo,allowed,branch,"other",null));
        }
    }

}
