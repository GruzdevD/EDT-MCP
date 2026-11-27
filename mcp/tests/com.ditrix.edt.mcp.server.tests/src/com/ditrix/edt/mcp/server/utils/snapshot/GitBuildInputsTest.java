/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils.snapshot;

import static org.junit.Assert.*;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.eclipse.core.resources.IProject;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.FileMode;
import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import org.mockito.Mockito;
import com.ditrix.edt.mcp.server.utils.snapshot.ProjectSourceManifest.Entry;
import com.ditrix.edt.mcp.server.utils.snapshot.ProjectSourceManifest.Version;

public class GitBuildInputsTest
{
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void extensionOrderAndDescriptorDigestArePartOfBuildIdentity()
    {
        var base = new GitBuildInputs.Input("Base","r","refs/heads/main","candidate","tree","base");
        var a = new GitBuildInputs.Input("ExtA","r","refs/heads/main","candidate","tree","inputs-A");
        var b = new GitBuildInputs.Input("ExtB","external","refs/heads/main","pinned-SHA","tree2","inputs-B");
        assertNotEquals(GitBuildInputs.manifestId(List.of(base,a,b)), GitBuildInputs.manifestId(List.of(base,b,a)));
        var revised = new GitBuildInputs.Input("ExtB","external","refs/heads/main","pinned-SHA","tree2","changed-descriptor");
        assertNotEquals(GitBuildInputs.manifestId(List.of(base,a,b)), GitBuildInputs.manifestId(List.of(base,a,revised)));
    }

    @Test
    public void committedPerimeterContainsOnlyConfiguredPathsAndBothTreeSides() throws Exception
    {
        var root = temp.newFolder().toPath();
        var review = new ReviewScope("app",root.toString(),Set.of("refs/heads/main"),Set.of(),Map.of(),Set.of(".github/workflows/test.yml"));
        var grant = new SnapshotSourcePolicy.Grant("Base",root.toString(),Set.of("bsl"),false,Set.of(),false,review);
        var scoped = new SnapshotSourcePolicy.ScopedProject(Mockito.mock(IProject.class),grant,root);
        var path = root.resolve(".github/workflows/test.yml");
        Files.createDirectories(path.getParent());
        Files.writeString(path,"disabled");
        Files.writeString(root.resolve("private.txt"),"hidden");
        try (var git = Git.init().setDirectory(root.toFile()).call())
        {
            git.add().addFilepattern(".").call();
            var before = git.commit().setMessage("first").setAuthor("fixture","test@example.invalid").setCommitter("fixture","test@example.invalid").call();
            Files.writeString(path,"enabled");
            git.add().addFilepattern(".").call();
            var after = git.commit().setMessage("second").setAuthor("fixture","test@example.invalid").setCommitter("fixture","test@example.invalid").call();
            var rows = ProjectCommitVersion.readPerimeter(git.getRepository(),scoped,"refs/heads/main",after,before).perimeter();
            assertEquals(1,rows.size());
            assertEquals(".github/workflows/test.yml",rows.get(0).path());
            assertArrayEquals("disabled".getBytes(),rows.get(0).head().bytes());
            assertArrayEquals("enabled".getBytes(),rows.get(0).disk().bytes());
        }
    }

    @Test
    public void externalDependencyUsesPinnedGitTreeEvenWhenWorkingTreeAdvances() throws Exception
    {
        var root = temp.newFolder("external").toPath();
        var grant = new SnapshotSourcePolicy.Grant("Base",root.toString(),Set.of("bsl","mdo"));
        var scoped = new SnapshotSourcePolicy.ScopedProject(Mockito.mock(IProject.class,Mockito.RETURNS_DEEP_STUBS),grant,root);
        for (var file : SnapshotBuildInputsTest.descriptors("","xtextBuilder").entrySet())
        {
            Files.createDirectories(root.resolve(file.getKey()).getParent());
            Files.write(root.resolve(file.getKey()),file.getValue().bytes());
        }
        var source = root.resolve("src/Caller.bsl");
        Files.createDirectories(source.getParent());
        Files.writeString(source,"pinned");
        try (var git = Git.init().setDirectory(root.toFile()).call())
        {
            git.getRepository().getConfig().setString("core",null,"autocrlf","false");
            git.add().addFilepattern(".").call();
            var pin = git.commit().setMessage("pin").setAuthor("fixture","test@example.invalid").setCommitter("fixture","test@example.invalid").call();
            Files.writeString(source,"later");
            git.add().addFilepattern(".").call();
            git.commit().setMessage("advance").setAuthor("fixture","test@example.invalid").setCommitter("fixture","test@example.invalid").call();
            var p = GitBuildInputs.project(git.getRepository(),scoped,pin,git.getRepository().getFullBranch(),List.of(),new GitBuildInputs.Budget());
            assertArrayEquals("pinned".getBytes(),p.entries().get(0).disk().bytes());
            assertEquals("later",Files.readString(source));
            assertFalse(grant.commit());
        }
    }

    @Test
    public void readsPinnedDescriptorAndForeignSourceFromGitAndAppliesOnlySelectedDiskBytes() throws Exception
    {
        var root = temp.newFolder("base").toPath();
        var grant = new SnapshotSourcePolicy.Grant("Base",root.toString(),Set.of("bsl","mdo"));
        var scope = new SnapshotSourcePolicy.ScopedProject(Mockito.mock(IProject.class,Mockito.RETURNS_DEEP_STUBS),grant,root);
        var files = SnapshotBuildInputsTest.descriptors("","xtextBuilder");
        for (var f : files.entrySet())
        {
            Files.createDirectories(root.resolve(f.getKey()).getParent());
            Files.write(root.resolve(f.getKey()), f.getValue().bytes());
        }
        Files.createDirectory(root.resolve("src"));
        Files.writeString(root.resolve("src/Existing.bsl"),"original");
        Files.writeString(root.resolve(".gitignore"),"src/Ignored.bsl\n");
        try (var git = Git.init().setDirectory(root.toFile()).call())
        {
            var repo = git.getRepository();
            repo.getConfig().setString("core",null,"autocrlf","false");
            repo.getConfig().save();
            git.add().addFilepattern(".").call();
            var parent = git.commit().setMessage("parent").setAuthor("fixture","test@example.invalid").setCommitter("fixture","test@example.invalid").call();
            Files.writeString(root.resolve(".project"),"stale descriptor");
            Files.writeString(root.resolve("src/Ignored.bsl"),"only live");
            Files.writeString(root.resolve("src/Existing.bsl"),"foreign");
            Files.writeString(root.resolve("src/New.bsl"),"selected");
            var project = GitBuildInputs.project(repo,scope,parent,repo.getFullBranch(),List.of(), new GitBuildInputs.Budget());
            var selected = new Entry("src/New.bsl",null,null,new Version(Files.readAllBytes(root.resolve("src/New.bsl"))));
            var choice = new ProjectCommitPlan.SelectedEntry(UUID.randomUUID(),project,grant,selected,selected.path());
            var change = new ProjectCommitPlan.Change(choice,FileMode.REGULAR_FILE,null);
            var actual = GitBuildInputs.project(repo,scope,parent,repo.getFullBranch(),List.of(change),new GitBuildInputs.Budget());
            assertEquals(List.of("src/Existing.bsl","src/New.bsl"),actual.entries().stream().map(Entry::path).toList());
            assertArrayEquals("original".getBytes(),actual.entries().get(0).disk().bytes());
            assertEquals(files.get(".project").contentId(),actual.buildInputs().files().get(".project").contentId());
            git.add().addFilepattern("src/Existing.bsl").call();
            var later = git.commit().setMessage("later").setAuthor("fixture","test@example.invalid").setCommitter("fixture","test@example.invalid").call();
            assertNotEquals(project.entries().get(0).disk().contentId(),GitBuildInputs.project(repo,scope,later,repo.getFullBranch(),
                List.of(),new GitBuildInputs.Budget()).entries().get(0).disk().contentId());
            repo.getConfig().setString("core",null,"autocrlf","true");
            assertThrows(IOException.class, () -> GitBuildInputs.project(repo,scope,parent,repo.getFullBranch(),List.of(),new GitBuildInputs.Budget()));
        }
    }
}
