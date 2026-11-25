/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils.snapshot;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.dircache.DirCacheEntry;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.Repository;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.ditrix.edt.mcp.server.utils.snapshot.ProjectSnapshotCapture.ModelStatus;
import com.ditrix.edt.mcp.server.utils.snapshot.ProjectSnapshotCapture.Snapshot;
import com.ditrix.edt.mcp.server.utils.snapshot.ProjectSnapshotPages.Row;
import com.ditrix.edt.mcp.server.utils.snapshot.ProjectSnapshotPages.View;
import com.ditrix.edt.mcp.server.utils.snapshot.ProjectSnapshotRegistry.Retained;
import com.ditrix.edt.mcp.server.utils.snapshot.ProjectSourceManifest.Entry;
import com.ditrix.edt.mcp.server.utils.snapshot.ProjectSourceManifest.Project;
import com.ditrix.edt.mcp.server.utils.snapshot.ProjectSourceManifest.Version;
import com.ditrix.edt.mcp.server.utils.snapshot.SnapshotSourcePolicy.Grant;

public class ProjectCommitPlanTest
{
    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void buildsSelectedTreeWithoutWritingObjectsIndexBranchOrWorkingFiles() throws Exception
    {
        Path root = temp.newFolder().toPath();
        try (Git git = Git.init().setDirectory(root.toFile()).call())
        {
            Repository repo = git.getRepository();
            repo.getConfig().setString("core", null, "autocrlf", "false");
            Path src = Files.createDirectory(root.resolve("src"));
            Files.writeString(src.resolve("Modify.bsl"), "base\n");
            Files.writeString(src.resolve("Delete.bsl"), "delete\n");
            Files.writeString(src.resolve("Old.bsl"), "move\n");
            Files.writeString(src.resolve("Unrelated.bsl"), "unrelated before\n");
            git.add().addFilepattern(".").call();
            var parent = git.commit().setMessage("Parent").setAuthor("Test", "test@example.invalid")
                .setCommitter("Test", "test@example.invalid").call();

            Files.writeString(src.resolve("Modify.bsl"), "modified\n");
            Files.writeString(src.resolve("Add.bsl"), "added\n");
            Files.delete(src.resolve("Delete.bsl"));
            Files.move(src.resolve("Old.bsl"), src.resolve("New.bsl"));
            Files.writeString(src.resolve("Unrelated.bsl"), "unrelated staged\n");
            git.add().addFilepattern("src/Unrelated.bsl").call();
            byte[] index = Files.readAllBytes(repo.getIndexFile().toPath());

            List<Entry> sources = List.of(entry("Add", null, null, "added\n"),
                entry("Delete", "delete\n", "delete\n", null),
                entry("Modify", "base\n", "base\n", "modified\n"),
                entry("New", null, null, "move\n"), entry("Old", "move\n", "move\n", null),
                entry("Unrelated", "unrelated before\n", "unrelated staged\n", "unrelated staged\n"));
            Retained captured = retained(repo, ModelStatus.UNKNOWN, sources);
            List<Row> rows = new ProjectSnapshotPages().read(captured, null, View.MANIFEST, null, null, 20).entries();
            var ids = rows.stream().filter(row -> !row.path().equals("src/Unrelated.bsl")).map(Row::entryId).toList();
            var selection = ProjectCommitPlan.select(captured, ids);
            assertEquals(ModelStatus.UNKNOWN, selection.modelStatus());
            assertEquals(Set.of("src/New.bsl", "src/Old.bsl"), Set.copyOf(selection.renamePeers().values()));
            assertThrows(IOException.class, () -> ProjectCommitPlan.select(captured, List.of(rows.stream()
                .filter(row -> row.path().equals("src/New.bsl")).findFirst().orElseThrow().entryId())));
            var plan = ProjectCommitPlan.build(repo, selection, "Selected source changes");

            assertEquals(parent, repo.resolve(Constants.HEAD));
            assertArrayEquals(index, Files.readAllBytes(repo.getIndexFile().toPath()));
            assertEquals("modified\n", Files.readString(src.resolve("Modify.bsl")));
            assertEquals("unrelated staged\n", Files.readString(src.resolve("Unrelated.bsl")));
            try (var reader = repo.newObjectReader())
            {
                assertFalse(reader.has(plan.tree()));
            }

            Files.writeString(src.resolve("Unrelated.bsl"), "unrelated before\n");
            git.add().addFilepattern(".").call();
            git.add().setUpdate(true).addFilepattern(".").call();
            var expected = git.commit().setMessage("Expected").setAuthor("Test", "test@example.invalid")
                .setCommitter("Test", "test@example.invalid").call();
            assertEquals(expected.getTree(), plan.tree());
        }
    }

    @Test
    public void checksCurrentBranchScopeIndexAndExternalPolicy() throws Exception
    {
        Path root = temp.newFolder().toPath();
        try (Git git = Git.init().setDirectory(root.toFile()).call())
        {
            Repository repo = git.getRepository();
            Files.writeString(Files.createDirectory(root.resolve("src")).resolve("Change.bsl"), "base\n");
            git.add().addFilepattern(".").call();
            var parent = git.commit().setMessage("Parent").setAuthor("Test", "test@example.invalid")
                .setCommitter("Test", "test@example.invalid").call();
            String ref = repo.getFullBranch();
            var allowed = new Grant("Configuration", root.toString(), Set.of("bsl"), false, Set.of(ref), false);
            var denied = new Grant("Configuration", root.toString(), Set.of("bsl"));
            Retained captured = retained(repo, ModelStatus.UNKNOWN, List.of(entry("Change", "base\n", "base\n", "edit\n")));
            UUID id = ProjectSnapshotPages.entryId(captured.id(), "Configuration", "src/Change.bsl");
            var selection = ProjectCommitPlan.select(captured, List.of(id));
            var plan = ProjectCommitPolicy.preview(repo, selection, List.of(allowed), "Commit selected source");
            assertEquals(parent, plan.selection().parent());
            assertThrows(IOException.class,
                () -> ProjectCommitPolicy.preview(repo, selection, List.of(denied), "Outside grant"));

            repo.getConfig().setBoolean("commit", null, "gpgSign", true);
            assertThrows(IOException.class,
                () -> ProjectCommitPolicy.preview(repo, selection, List.of(allowed), "Must be signed"));
            repo.getConfig().unset("commit", null, "gpgSign");
            Path hook = Files.writeString(repo.getDirectory().toPath().resolve("hooks/commit-msg"), "hook\n");
            assertThrows(IOException.class,
                () -> ProjectCommitPolicy.preview(repo, selection, List.of(allowed), "Requires hook"));
            Files.delete(hook);

            git.checkout().setName(parent.name()).call();
            assertThrows(IOException.class,
                () -> ProjectCommitPolicy.preview(repo, selection, List.of(allowed), "Detached HEAD"));
            git.checkout().setName(Repository.shortenRefName(ref)).call();

            Files.writeString(root.resolve("src/Change.bsl"), "stage a different version\n");
            git.add().addFilepattern("src/Change.bsl").call();
            Retained partial = retained(repo, ModelStatus.UNKNOWN,
                List.of(entry("Change", "base\n", "stage a different version\n", "edit\n")));
            var partlyStaged = ProjectCommitPlan.select(partial,
                List.of(ProjectSnapshotPages.entryId(partial.id(), "Configuration", "src/Change.bsl")));
            IOException refusal = assertThrows(IOException.class,
                () -> ProjectCommitPolicy.preview(repo, partlyStaged, List.of(allowed), "Partly staged"));
            assertEquals("A selected file is partially staged. Align its index and disk versions first.", refusal.getMessage());

            var index = repo.lockDirCache();
            try
            {
                var conflict = new DirCacheEntry("unrelated.bsl", DirCacheEntry.STAGE_2);
                var tracked = index.getEntry("src/Change.bsl");
                conflict.setFileMode(tracked.getFileMode());
                conflict.setObjectId(tracked.getObjectId());
                var builder = index.builder();
                builder.add(tracked);
                builder.add(conflict);
                assertTrue(builder.commit());
            }
            finally
            {
                index.unlock();
            }
            refusal = assertThrows(IOException.class,
                () -> ProjectCommitPolicy.preview(repo, partlyStaged, List.of(allowed), "Unrelated conflict"));
            assertEquals("Resolve Git index conflicts before previewing or confirming a project commit.", refusal.getMessage());
        }
    }

    @Test
    public void confirmationBindsOwnerLifetimePermissionConsentAndExactDiskState() throws Exception
    {
        Path root = temp.newFolder().toPath();
        try (Git git = Git.init().setDirectory(root.toFile()).call())
        {
            Repository repo = git.getRepository();
            Files.writeString(Files.createDirectory(root.resolve("src")).resolve("Change.bsl"), "base\n");
            git.add().addFilepattern(".").call();
            git.commit().setMessage("Parent").setAuthor("Test", "test@example.invalid")
                .setCommitter("Test", "test@example.invalid").call();
            List<Entry> original = List.of(entry("Change", "base\n", "base\n", "selected\n"));
            Retained retained = retained(repo, ModelStatus.UNKNOWN, original);
            var plan = ProjectCommitPlan.build(repo, ProjectCommitPlan.select(retained, List.of(
                ProjectSnapshotPages.entryId(retained.id(), "Configuration", "src/Change.bsl"))), "Reviewed message");
            var allow = new Grant("Configuration", root.toString(), Set.of("bsl"), false,
                Set.of(repo.getFullBranch()), true);
            var deny = new Grant("Configuration", root.toString(), Set.of("bsl"), false,
                Set.of(repo.getFullBranch()), false);
            Clock clock = mock(Clock.class);
            when(clock.instant()).thenReturn(retained.expiresAt().minusSeconds(60));
            var requests = new ProjectCommitRequests<String>(clock);
            var review = requests.retain("owner", retained, plan);
            assertEquals("Reviewed message", review.message());
            assertEquals(plan.tree().name(), review.tree());
            assertEquals(ModelStatus.UNKNOWN, review.modelStatus());
            assertEquals(retained.expiresAt(), review.expiresAt());
            assertThrows(IOException.class, () -> requests.require("different owner", review.id()));

            assertThrows(IOException.class, () -> requests.authorize("owner", review.id(), ignored -> List.of(deny),
                ignored -> { throw new AssertionError("Consent called without write access"); }, ignored -> retained.snapshot()));
            assertThrows(IOException.class, () -> requests.authorize("owner", review.id(), ignored -> List.of(allow),
                ignored -> { throw new IOException("declined"); },
                ignored -> { throw new AssertionError("Capture called after consent was declined"); }));

            var currentGrant = new AtomicReference<>(allow);
            assertThrows(IOException.class, () -> requests.authorize("owner", review.id(),
                ignored -> List.of(currentGrant.get()), ignored -> currentGrant.set(deny), ignored -> retained.snapshot()));
            var replacement = retained(repo, ModelStatus.UNKNOWN,
                List.of(entry("Change", "base\n", "base\n", "different disk bytes\n")));
            assertThrows(IOException.class, () -> requests.authorize("owner", review.id(), ignored -> List.of(allow),
                ignored -> { }, ignored -> replacement.snapshot()));
            var diverged = retained(repo, ModelStatus.DIVERGED, original);
            assertThrows(IOException.class, () -> requests.authorize("owner", review.id(), ignored -> List.of(allow),
                ignored -> { }, ignored -> diverged.snapshot()));
            assertEquals(review, requests.authorize("owner", review.id(), ignored -> List.of(allow),
                ignored -> { }, ignored -> retained.snapshot()));
            when(clock.instant()).thenReturn(review.expiresAt());
            assertThrows(IOException.class, () -> requests.require("owner", review.id()));
        }
    }

    @Test
    public void refusesEntriesFromDifferentRepositories() throws Exception
    {
        Path base = temp.newFolder().toPath();
        Path other = temp.newFolder().toPath();
        String parent = "1111111111111111111111111111111111111111";
        var configuration = new Project("Configuration", "refs/heads/main", parent,
            List.of(entry("Add", null, null, "new")), false, null, base);
        var extension = new Project("Extension", configuration.branch(), parent,
            List.of(entry("Add", null, null, "new")), false, null, other);
        var id = UUID.randomUUID();
        var snapshot = new Snapshot(List.of(configuration, extension), Map.of(), ModelStatus.UNKNOWN);
        var retained = new Retained(id, Instant.now(), List.of(
            new Grant("Configuration", base.toString(), Set.of("bsl")),
            new Grant("Extension", other.toString(), Set.of("bsl"))), snapshot);
        assertThrows(IOException.class, () -> ProjectCommitPlan.select(retained, List.of(
            ProjectSnapshotPages.entryId(id, "Configuration", "src/Add.bsl"),
            ProjectSnapshotPages.entryId(id, "Extension", "src/Add.bsl"))));
    }

    @Test
    public void rejectsDivergenceAndUnrecognizedIds() throws Exception
    {
        try (Git git = Git.init().setDirectory(temp.newFolder()).call())
        {
            var repo = git.getRepository();
            var sources = List.of(entry("Add", null, null, "new"));
            Retained diverged = retained(repo, ModelStatus.DIVERGED, sources);
            UUID id = ProjectSnapshotPages.entryId(diverged.id(), "Configuration", "src/Add.bsl");
            assertThrows(IOException.class, () -> ProjectCommitPlan.select(diverged, List.of(id)));
            Retained unknown = retained(repo, ModelStatus.UNKNOWN, sources);
            assertThrows(IOException.class, () -> ProjectCommitPlan.select(unknown, List.of(id)));
        }
    }

    private static Retained retained(Repository repo, ModelStatus status, List<Entry> entries) throws IOException
    {
        Path root = repo.getWorkTree().toPath();
        var parent = repo.resolve(Constants.HEAD);
        var project = new Project("Configuration", repo.getFullBranch(), parent == null ? null : parent.name(),
            entries, false, null, root);
        return new Retained(UUID.randomUUID(), Instant.now().plusSeconds(60),
            List.of(new Grant("Configuration", root.toString(), Set.of("bsl"))),
            new Snapshot(List.of(project), Map.of(), status));
    }

    private static Entry entry(String path, String head, String index, String disk)
    {
        return new Entry("src/" + path + ".bsl", version(head), version(index), version(disk));
    }

    private static Version version(String contents)
    {
        return contents == null ? null : new Version(contents.getBytes(StandardCharsets.UTF_8));
    }
}
