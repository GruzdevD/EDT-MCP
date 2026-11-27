/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils.snapshot;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevWalk;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.ditrix.edt.mcp.server.utils.snapshot.ProjectCommitExecution.Prepared;
import com.ditrix.edt.mcp.server.utils.snapshot.ProjectCommitExecution.SourceGuard;
import com.ditrix.edt.mcp.server.utils.snapshot.ProjectCommitPlan.Plan;
import com.ditrix.edt.mcp.server.utils.snapshot.ProjectSnapshotCapture.ModelStatus;
import com.ditrix.edt.mcp.server.utils.snapshot.ProjectSnapshotCapture.Snapshot;
import com.ditrix.edt.mcp.server.utils.snapshot.ProjectSnapshotRegistry.Retained;
import com.ditrix.edt.mcp.server.utils.snapshot.ProjectSourceManifest.Entry;
import com.ditrix.edt.mcp.server.utils.snapshot.ProjectSourceManifest.Project;
import com.ditrix.edt.mcp.server.utils.snapshot.ProjectSourceManifest.Version;
import com.ditrix.edt.mcp.server.utils.snapshot.SnapshotSourcePolicy.Grant;

public class ProjectCommitExecutionTest
{
    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Before
    public void requireWindows()
    {
        assumeTrue(System.getProperty("os.name").startsWith("Windows"));
    }

    @Test
    public void commitsReviewedTreeAndPreservesOtherChangesIncludingALateCreation() throws Exception
    {
        try (Git git = init())
        {
            Repository repo = git.getRepository();
            Path root = repo.getWorkTree().toPath();
            var plan = plan(repo);
            var tree = WindowsSourceTree.load();
            var committed = ProjectSourceManifest.withCommitRepository(tree, root, (writer, release) ->
                ProjectCommitExecution.commit(writer, plan, release,
                    () -> restoreAfterCheck(tree, plan, root.resolve("src/Delete.bsl")), () -> { }, new Journal()));
            try (var revisions = new RevWalk(repo))
            {
                var actual = revisions.parseCommit(repo.resolve(Constants.HEAD));
                assertEquals(committed.commit(), actual.name());
                assertEquals(plan.tree(), actual.getTree());
                assertEquals(plan.selection().parent(), actual.getParent(0));
                assertEquals(plan.message(), actual.getFullMessage());
            }
            var status = git.status().call();
            assertEquals(Set.of("src/Other.bsl"), status.getChanged());
            assertEquals(Set.of("src/Other.bsl"), status.getModified());
            assertEquals(Set.of("src/Delete.bsl"), status.getUntracked());
            assertEquals("created after check\n", Files.readString(root.resolve("src/Delete.bsl")));
        }
    }

    @Test
    public void refusesAnEarlyRestorationAndABranchChangeWithoutUpdatingIndexOrRef() throws Exception
    {
        try (Git git = init())
        {
            Repository repo = git.getRepository();
            Path root = repo.getWorkTree().toPath();
            var plan = plan(repo);
            var tree = WindowsSourceTree.load();
            byte[] index = Files.readAllBytes(repo.getIndexFile().toPath());
            var recorder = new Journal();
            assertThrows(IOException.class, () -> ProjectSourceManifest.withCommitRepository(tree, root,
                (writer, release) -> ProjectCommitExecution.commit(writer, plan, release,
                    () -> new ProjectCommitSources(tree, plan.changes()),
                    () -> Files.writeString(root.resolve("src/Delete.bsl"), "restored early\n"), recorder)));
            assertEquals(List.of("prepare", "discard"), recorder.steps);
            assertEquals(plan.selection().parent(), repo.resolve(Constants.HEAD));
            assertArrayEquals(index, Files.readAllBytes(repo.getIndexFile().toPath()));

            git.checkout().setCreateBranch(true).setName("other").call();
            byte[] otherIndex = Files.readAllBytes(repo.getIndexFile().toPath());
            assertThrows(IOException.class, () -> ProjectSourceManifest.withCommitRepository(tree, root,
                (writer, release) -> ProjectCommitExecution.commit(writer, plan, release,
                    () -> new ProjectCommitSources(tree, plan.changes()), () -> { }, new Journal())));
            assertEquals(plan.selection().parent(), repo.resolve(Constants.HEAD));
            assertEquals(plan.selection().parent(), repo.resolve(plan.selection().branch()));
            assertArrayEquals(otherIndex, Files.readAllBytes(repo.getIndexFile().toPath()));
        }
    }

    @Test
    public void reopensAPreparedReceiptAndRepairsTheIndexWithoutRecreatingTheCommit() throws Exception
    {
        try (Git git = init())
        {
            Repository repo = git.getRepository();
            Path root = repo.getWorkTree().toPath();
            Path journalPath = temp.newFolder().toPath();
            var plan = plan(repo);
            var tree = WindowsSourceTree.load();
            UUID id = UUID.randomUUID();
            interruptAfterRef(plan, tree, root, journalPath, id);
            String originalSha = repo.resolve(Constants.HEAD).name();
            Files.writeString(root.resolve("src/Later.bsl"), "later staged\n");
            git.add().addFilepattern("src/Later.bsl").call();
            Files.writeString(root.resolve("src/Change.bsl"), "later ordinary edit\n");
            try (var journal = ProjectCommitJournal.open(journalPath, Clock.systemUTC()))
            {
                assertThrows(IOException.class, () -> journal.find(id, "different owner"));
                var receipt = journal.find(id, "owner");
                assertEquals(ProjectCommitJournal.Phase.PREPARED, receipt.phase());
                assertEquals(originalSha, receipt.prepared().commit());
                var result = ProjectSourceManifest.withCommitRepository(tree, root,
                    (writer, release) -> ProjectCommitExecution.recover(writer, receipt.prepared(), release));
                assertEquals(ProjectCommitExecution.Recovery.COMMITTED, result);
                journal.finish(id, receipt);
            }
            try (var journal = ProjectCommitJournal.open(journalPath, Clock.offset(Clock.systemUTC(),java.time.Duration.ofHours(24))))
            {
                var receipt = journal.find(id, "owner");
                assertEquals(ProjectCommitJournal.Phase.COMMITTED, receipt.phase());
                assertEquals(originalSha, receipt.prepared().commit());
                assertEquals(originalSha, repo.resolve(Constants.HEAD).name());
            }
            var status = git.status().call();
            assertEquals(Set.of("src/Later.bsl"), status.getAdded());
            assertEquals(Set.of("src/Other.bsl"), status.getChanged());
            assertEquals(Set.of("src/Other.bsl", "src/Change.bsl"), status.getModified());
            assertEquals("later ordinary edit\n", Files.readString(root.resolve("src/Change.bsl")));
        }
    }

    @Test
    public void refusesToRecoverOverALaterSelectedIndexChange() throws Exception
    {
        try (Git git = init())
        {
            Repository repo = git.getRepository();
            Path root = repo.getWorkTree().toPath();
            Path journalPath = temp.newFolder().toPath();
            var plan = plan(repo);
            var tree = WindowsSourceTree.load();
            UUID id = UUID.randomUUID();
            interruptAfterRef(plan, tree, root, journalPath, id);
            Files.writeString(root.resolve("src/Change.bsl"), "later staged change\n");
            git.add().addFilepattern("src/Change.bsl").call();
            byte[] index = Files.readAllBytes(repo.getIndexFile().toPath());
            try (var journal = ProjectCommitJournal.open(journalPath, Clock.systemUTC()))
            {
                var receipt = journal.find(id, "owner");
                var result = ProjectSourceManifest.withCommitRepository(tree, root,
                    (writer, release) -> ProjectCommitExecution.recover(writer, receipt.prepared(), release));
                assertEquals(ProjectCommitExecution.Recovery.RECOVERY_REQUIRED, result);
                assertEquals(receipt.prepared().commit(), repo.resolve(Constants.HEAD).name());
                assertArrayEquals(index, Files.readAllBytes(repo.getIndexFile().toPath()));
            }
        }
    }

    private static void interruptAfterRef(Plan plan, WindowsSourceTree tree, Path root, Path journalPath, UUID id)
        throws IOException
    {
        try (var journal = ProjectCommitJournal.open(journalPath, Clock.systemUTC()))
        {
            List<Grant> grants = List.of(plan.selection().entries().get(0).grant());
            var persisted = journal.begin(id, "owner", new ProjectCommitJournal.Scope(root.toString(), grants, grants,
                new BuildReview("app","verify-rate","Base",List.of(),List.of())));
            var recorder = new Journal()
            {
                @Override
                public void prepare(Prepared prepared) throws IOException
                {
                    persisted.prepare(prepared);
                }

                @Override
                public void published() throws IOException
                {
                    throw new IOException("Simulated interruption");
                }
            };
            IOException failure = assertThrows(IOException.class, () -> ProjectSourceManifest.withCommitRepository(tree, root,
                (writer, release) -> ProjectCommitExecution.commit(writer, plan, release,
                    () -> new ProjectCommitSources(tree, plan.changes()), () -> { }, recorder)));
            assertEquals("Simulated interruption", failure.getMessage());
            assertTrue(Files.exists(journalPath.resolve(id + ".json")));
        }
    }

    @Test
    public void recoveryBeforePublicationAndAfterIndexPublication() throws Exception
    {
        for (boolean afterIndex : List.of(false,true))
        {
            try (Git git = init())
            {
                var repo = git.getRepository();
                var root = repo.getWorkTree().toPath();
                var plan = plan(repo);
                var tree = WindowsSourceTree.load();
                Prepared[] durable = new Prepared[1];
                var fault = new ProjectCommitExecution.Journal()
                {
                    public void prepare(Prepared value) throws IOException
                    {
                        durable[0] = value;
                        if (!afterIndex) { throw new IOException("Interruption before ref publication"); }
                    }
                    public void published() { }
                    public void discard() { }
                    public void finish() throws IOException { throw new IOException("Interruption after index publication"); }
                };
                assertThrows(IOException.class,() -> ProjectSourceManifest.withCommitRepository(tree,root,(writer,release) ->
                    ProjectCommitExecution.commit(writer,plan,release,() -> new ProjectCommitSources(tree,plan.changes()),() -> { },fault)));
                String head = repo.resolve(Constants.HEAD).name();
                var recovered = ProjectSourceManifest.withCommitRepository(tree,root,(writer,release) ->
                    ProjectCommitExecution.recover(writer,durable[0],release));
                assertEquals(afterIndex ? ProjectCommitExecution.Recovery.COMMITTED : ProjectCommitExecution.Recovery.RECOVERY_REQUIRED,recovered);
                assertEquals(afterIndex ? durable[0].commit() : durable[0].parent(),head);
                assertEquals(head,repo.resolve(Constants.HEAD).name());
            }
        }
    }

    private static SourceGuard restoreAfterCheck(WindowsSourceTree tree, Plan plan, Path file) throws IOException
    {
        var sources = new ProjectCommitSources(tree, plan.changes());
        return new SourceGuard()
        {
            @Override
            public void checkDeletedPaths() throws IOException
            {
                sources.checkDeletedPaths();
                Files.writeString(file, "created after check\n");
            }

            @Override
            public void close()
            {
                sources.close();
            }
        };
    }

    private Git init() throws Exception
    {
        Path root = temp.newFolder().toPath();
        Git git = Git.init().setDirectory(root.toFile()).call();
        var config = git.getRepository().getConfig();
        config.setString("user", null, "name", "Fixture");
        config.setString("user", null, "email", "fixture@example.invalid");
        config.setBoolean("core", null, "autocrlf", false);
        config.save();
        Path src = Files.createDirectory(root.resolve("src"));
        Files.writeString(src.resolve("Change.bsl"), "old\n");
        Files.writeString(src.resolve("Delete.bsl"), "delete\n");
        Files.writeString(src.resolve("Other.bsl"), "other\n");
        git.add().addFilepattern(".").call();
        git.commit().setMessage("Parent").call();
        Files.writeString(src.resolve("Change.bsl"), "selected\n");
        Files.delete(src.resolve("Delete.bsl"));
        Files.writeString(src.resolve("Other.bsl"), "other staged\n");
        git.add().addFilepattern("src/Other.bsl").call();
        Files.writeString(src.resolve("Other.bsl"), "other unstaged\n");
        return git;
    }

    private static Plan plan(Repository repo) throws IOException
    {
        return plan(repo,"selected\n");
    }

    private static Plan plan(Repository repo, String selectedText) throws IOException
    {
        Path root = repo.getWorkTree().toPath();
        String branch = repo.getFullBranch();
        ObjectId parent = repo.resolve(Constants.HEAD);
        var entries = List.of(new Entry("src/Change.bsl", version("old\n"), version("old\n"), version(selectedText)),
            new Entry("src/Delete.bsl", version("delete\n"), version("delete\n"), null));
        var project = new Project("Configuration", branch, parent.name(), entries, false, null, root);
        var grant = new Grant("Configuration", root.toString(), Set.of("bsl"), false, Set.of(branch), true);
        var retained = new Retained(UUID.randomUUID(), Instant.now().plusSeconds(60), List.of(grant),
            new Snapshot(List.of(project), Map.of(), ModelStatus.UNKNOWN));
        var ids = entries.stream().map(entry -> ProjectSnapshotPages.entryId(retained.id(), project.name(), entry.path())).toList();
        return ProjectCommitPolicy.preview(repo, ProjectCommitPlan.select(retained, ids), List.of(grant), "Reviewed message\n");
    }

    @Test
    public void stagesNewBslAndMetadataAgainstThePinnedDiskWhilePreservingForeignIndex() throws Exception
    {
        try (Git git = init())
        {
            var repo = git.getRepository();
            var root = repo.getWorkTree().toPath();
            var head = repo.resolve(Constants.HEAD);
            var branch = repo.getFullBranch();
            var foreign = repo.readDirCache().getEntry("src/Other.bsl").getObjectId();
            var entries = new java.util.ArrayList<Entry>();
            for (String path : List.of("src/New.bsl", "src/New.mdo"))
            {
                Files.writeString(root.resolve(path), "new source");
                entries.add(new Entry(path, null, null, version("new source")));
            }
            var project = new Project("App", branch, head.name(), entries, false, null, root);
            var grant = new Grant("App", root.toString(), Set.of("bsl", "mdo"));
            var captured = new Retained(UUID.randomUUID(), Instant.now().plusSeconds(60), List.of(grant),
                new Snapshot(List.of(project), Map.of(), ModelStatus.UNKNOWN));
            var ids = entries.stream().map(e -> ProjectSnapshotPages.entryId(captured.id(), "App", e.path())).toList();
            Plan plan = ProjectCommitPlan.build(repo, ProjectCommitPlan.select(captured, ids), "stage");
            var tree = WindowsSourceTree.load();
            var receipt = ProjectSourceManifest.withCommitRepository(tree, root, (r, release) ->
                ProjectCommitExecution.stage(r, plan, release, () -> new ProjectCommitSources(tree, plan.changes()), () -> {}));
            assertEquals(2, receipt.size());
            for (var item : receipt)
            {
                assertEquals(item.diskBlob(), item.indexBlob());
                assertEquals(repo.readDirCache().getEntry(item.path()).getObjectId().name(), item.indexBlob());
            }
            assertEquals(head, repo.resolve(Constants.HEAD));
            assertEquals(foreign, repo.readDirCache().getEntry("src/Other.bsl").getObjectId());
        }
    }

    @Test
    public void commitsOnlySelectedArtifactRevisionWithThePreviewedParentAndTree() throws Exception
    {
        try (Git git = init())
        {
            var repo = git.getRepository();
            var selection = plan(repo).selection();
            var root = selection.workTree();
            var change = Files.createDirectories(root.resolve("openspec/changes/feature"));
            Files.writeString(change.resolve("proposal.md"), "do not select");
            var file = Files.writeString(change.resolve("bsl-prepass-exclusions.txt"), "approved bytes");
            var review = new ReviewScope("repo", root.toString(), Set.of(selection.branch()), Set.of(),
                Map.of("feature", Set.of("include","read")));
            var grant = new Grant("Configuration",root.toString(),Set.of("bsl"),false,Set.of(selection.branch()),true,review);
            var q = new ChangeArtifacts.Query("repo","Configuration",selection.branch(),"feature",null);
            var key = new ChangeArtifacts.Key("prepassExclusions",null);
            var artifact = new BuildReview.Artifact(key.kind(),null,ChangeArtifacts.revision(q,key,Files.readAllBytes(file)).revision());
            var build = new BuildReview("repo","feature","Configuration",List.of(artifact),List.of());
            var tree = WindowsSourceTree.load();
            var id = UUID.randomUUID();
            var picked = build.attach(selection,repo,tree,id,List.of(grant),List.of(selection.entries().get(0).project()));
            var preview = ProjectCommitPlan.build(repo,picked,"build");
            Files.writeString(file,"later");
            assertThrows(IOException.class, () -> build.attach(selection,repo,tree,id,List.of(grant),List.of(selection.entries().get(0).project())));
            Files.writeString(file,"approved bytes");
            var committed = ProjectSourceManifest.withCommitRepository(tree,root,(r,release) ->
                ProjectCommitExecution.commit(r,preview,release,() -> new ProjectCommitSources(tree,preview.changes()),
                    () -> {},new Journal()));
            assertEquals(preview.tree().name(),committed.tree());
            assertEquals(selection.parent().name(),committed.parent());
            assertEquals(preview.tree(),repo.resolve("HEAD^{tree}"));
            assertEquals(null,repo.resolve("HEAD:openspec/changes/feature/proposal.md"));
            assertEquals("approved bytes",new String(repo.open(repo.resolve("HEAD:openspec/changes/feature/bsl-prepass-exclusions.txt"))
                .getBytes(), java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    @Test
    public void stageAbortsAtomicallyIfADeletionReturnsOrTheIndexDiverges() throws Exception
    {
        try (Git git = init())
        {
            var repo = git.getRepository();
            var root = repo.getWorkTree().toPath();
            Plan plan = plan(repo);
            var tree = WindowsSourceTree.load();
            byte[] before = Files.readAllBytes(repo.getIndexFile().toPath());
            assertThrows(IOException.class, () -> ProjectSourceManifest.withCommitRepository(tree, root, (r, release) ->
                ProjectCommitExecution.stage(r, plan, release, () -> new ProjectCommitSources(tree, plan.changes()),
                    () -> Files.writeString(root.resolve("src/Delete.bsl"), "restored"))));
            assertArrayEquals(before, Files.readAllBytes(repo.getIndexFile().toPath()));
            Files.delete(root.resolve("src/Delete.bsl"));
            Files.writeString(root.resolve("src/Change.bsl"), "partial");
            git.add().addFilepattern("src/Change.bsl").call();
            Files.writeString(root.resolve("src/Change.bsl"), "selected\n");
            byte[] partial = Files.readAllBytes(repo.getIndexFile().toPath());
            assertThrows(IOException.class, () -> ProjectSourceManifest.withCommitRepository(tree, root, (r, release) ->
                ProjectCommitExecution.stage(r, plan, release, () -> new ProjectCommitSources(tree, plan.changes()), () -> {})));
            assertArrayEquals(partial, Files.readAllBytes(repo.getIndexFile().toPath()));
        }
    }

    private static Version version(String text)
    {
        return new Version(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static class Journal implements ProjectCommitExecution.Journal
    {
        private final List<String> steps = new java.util.ArrayList<>();

        @Override
        public void prepare(Prepared prepared) throws IOException
        {
            steps.add("prepare");
        }

        @Override
        public void discard()
        {
            steps.add("discard");
        }

        @Override
        public void published() throws IOException
        {
            steps.add("published");
        }

        @Override
        public void finish()
        {
            steps.add("finish");
        }
    }

    @Test public void stageRechecksSystemAttributesAndAcceptsDiffDrivers() throws Exception
    {
        var system = temp.newFolder().toPath();
        var attributes = system.resolve("gitattributes");
        try (var git = init();
             var repo = new org.eclipse.jgit.storage.file.FileRepositoryBuilder()
                 .setGitDir(git.getRepository().getDirectory())
                 .setFS(git.getRepository().getFS().newInstance().setGitSystemConfig(system.resolve("gitconfig").toFile())).build())
        {
            var root = repo.getWorkTree().toPath();
            Files.writeString(root.resolve("src/Change.bsl"),"selected\r\n");
            var plan = plan(repo,"selected\r\n");
            var before = Files.readAllBytes(repo.getIndexFile().toPath());
            String benign = "*.bsl diff=astextplain\n*.cpp text eol=lf\n";
            Files.writeString(attributes,benign + "*.bsl text eol=lf\n");
            assertThrows(IOException.class,() -> ProjectCommitExecution.stage(repo,plan,() -> {},
                () -> new ProjectCommitSources(WindowsSourceTree.load(),plan.changes()),() -> {}));
            assertArrayEquals(before,Files.readAllBytes(repo.getIndexFile().toPath()));
            assertEquals(plan.selection().parent(),repo.resolve(Constants.HEAD));
            Files.writeString(attributes,benign);
            assertEquals(2,ProjectCommitExecution.stage(repo,plan,() -> {},
                () -> new ProjectCommitSources(WindowsSourceTree.load(),plan.changes()),() -> {}).size());
        }
    }

    @Test public void stageRejectsUserAttributesCreatedAfterPreview() throws Exception
    {
        // Inject a private Git home into this repository only.
        org.junit.Assume.assumeTrue(System.getenv("XDG_CONFIG_HOME") == null || System.getenv("XDG_CONFIG_HOME").isBlank());
        var home = temp.newFolder().toPath();
        try (var git = init();
             var repo = new org.eclipse.jgit.storage.file.FileRepositoryBuilder()
                 .setGitDir(git.getRepository().getDirectory())
                 .setFS(git.getRepository().getFS().newInstance().setUserHome(home.toFile())).build())
        {
            var root = repo.getWorkTree().toPath();
            Files.writeString(root.resolve("src/Change.bsl"),"selected\r\n");
            var plan = plan(repo,"selected\r\n");
            var before = Files.readAllBytes(repo.getIndexFile().toPath());
            var attributes = Files.createDirectories(home.resolve(".config/git")).resolve("attributes");
            Files.writeString(attributes,"*.bsl text eol=lf\n");
            assertThrows(IOException.class,() -> ProjectCommitExecution.stage(repo,plan,() -> {},
                () -> new ProjectCommitSources(WindowsSourceTree.load(),plan.changes()),() -> {}));
            assertArrayEquals(before,Files.readAllBytes(repo.getIndexFile().toPath()));
            assertEquals(plan.selection().parent(),repo.resolve(Constants.HEAD));
        }
    }

    @Test public void stageRejectsChangedCrlfOrAttributesAndPreservesIndex() throws Exception
    {
        for (boolean changeConfig : List.of(false,true))
        {
            try (Git git = init())
            {
                var repo = git.getRepository();
                var root = repo.getWorkTree().toPath();
                var selection = plan(repo);
                var tree = WindowsSourceTree.load();
                byte[] indexBefore = Files.readAllBytes(repo.getIndexFile().toPath());
                if (changeConfig)
                {
                    repo.getConfig().setString("core",null,"autocrlf","true");
                    repo.getConfig().save();
                }
                else { Files.writeString(root.resolve("src/.gitattributes"),"*.bsl text eol=lf"); }
                assertThrows(IOException.class,() -> ProjectSourceManifest.withCommitRepository(tree,root,(r,release) ->
                    ProjectCommitExecution.stage(r,selection,release,() -> new ProjectCommitSources(tree,selection.changes()),() -> {})));
                assertArrayEquals(indexBefore,Files.readAllBytes(repo.getIndexFile().toPath()));
                assertEquals(selection.selection().parent(),repo.resolve(Constants.HEAD));
            }
        }
    }

    @Test public void extensionOnlySelectionUsesChangeGrantOfCapturedBase() throws Exception
    {
        try (var git = init())
        {
            var repo = git.getRepository();
            var selection = plan(repo).selection();
            var root = selection.workTree();
            Files.writeString(Files.createDirectories(root.resolve("openspec/changes/feature")).resolve("proposal.md"),"proposal");
            var review = new ReviewScope("repo",root.toString(),Set.of(selection.branch()),Set.of(),Map.of("feature",Set.of("read")));
            var base = new Project("Base",selection.branch(),selection.parent().name(),List.of(),false,null,root);
            var baseGrant = new Grant("Base",root.resolve("Base").toString(),Set.of("bsl"),true,Set.of(),false,review);
            var extension = selection.entries().get(0).project();
            var build = new BuildReview("repo","feature","Base",List.of(),List.of());
            var picked = build.attach(selection,repo,WindowsSourceTree.load(),UUID.randomUUID(),
                List.of(baseGrant,selection.entries().get(0).grant()),List.of(base,extension));
            assertEquals(selection.entries(),picked.entries());
            assertEquals(ProjectCommitPlan.build(repo,selection,"build").tree(),ProjectCommitPlan.build(repo,picked,"build").tree());
        }
    }

}
