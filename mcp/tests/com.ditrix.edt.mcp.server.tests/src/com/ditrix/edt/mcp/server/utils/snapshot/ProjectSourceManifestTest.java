/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils.snapshot;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.eclipse.core.resources.IProject;
import org.eclipse.jgit.api.Git;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.ditrix.edt.mcp.server.utils.snapshot.ProjectSourceManifest.Entry;
import com.ditrix.edt.mcp.server.utils.snapshot.ProjectSourceManifest.Version;
import com.ditrix.edt.mcp.server.utils.snapshot.SnapshotSourcePolicy.Access;
import com.ditrix.edt.mcp.server.utils.snapshot.SnapshotSourcePolicy.Grant;
import com.ditrix.edt.mcp.server.utils.snapshot.SnapshotSourcePolicy.ScopedProject;

public class ProjectSourceManifestTest
{
    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Before
    public void requireWindows()
    {
        assumeTrue(System.getProperty("os.name").startsWith("Windows"));
    }

    @Test
    public void buildKeepsAuthorizedIgnoredFilesWhileReviewHonorsGitIgnore() throws Exception
    {
        Path project = temp.newFolder("build-project").toPath();
        try (Git git = Git.init().setDirectory(project.toFile()).call())
        {
            write(project, ".project", """
                <projectDescription><name>Allowed</name><projects/>
                <buildSpec><buildCommand><name>org.eclipse.xtext.ui.shared.xtextBuilder</name>
                <arguments/></buildCommand></buildSpec><natures>
                <nature>org.eclipse.xtext.ui.shared.xtextNature</nature>
                <nature>com._1c.g5.v8.dt.core.V8ConfigurationNature</nature>
                </natures></projectDescription>
                """);
            write(project, "DT-INF/PROJECT.PMF", "Manifest-Version: 1.0\nRuntime-Version: 8.5.1\n");
            write(project, "src/Configuration/Configuration.mdo", """
                <m:Configuration xmlns:m="http://g5.1c.ru/v8/dt/metadata/mdclass"
                uuid="38ecdf43-3c5f-49b7-b0aa-26d365a57ee6"><name>Allowed</name></m:Configuration>
                """);
            write(project, ".gitignore", "src/generated/\n");
            write(project, ".git/info/exclude", "local.bsl\n");
            write(project, "src/generated/extra.bsl", "ignored directory\n");
            write(project, "src/local.bsl", "ignored file\n");
            git.add().addFilepattern(".").call();
            git.commit().setMessage("fixture").setAuthor("Fixture", "fixture@example.invalid")
                .setCommitter("Fixture", "fixture@example.invalid").call();

            Grant grant = new Grant("Allowed", project.toString(), Set.of("bsl", "mdo"), true);
            ScopedProject scope = new ScopedProject(mock(IProject.class, RETURNS_DEEP_STUBS), grant, project);
            Access access = new Access(null, WindowsSourceTree.load(), List.of(scope));
            var review = ProjectSourceManifest.capture(access, false).get(0);
            assertEquals(List.of("src/Configuration/Configuration.mdo"), review.entries().stream().map(Entry::path).toList());
            var build = ProjectSourceManifest.capture(access, true).get(0);
            Map<String, Entry> entries = build.entries().stream().collect(Collectors.toMap(Entry::path, Function.identity()));
            assertEquals("ignored directory\n", text(entries.get("src/generated/extra.bsl").disk()));
            assertEquals("ignored file\n", text(entries.get("src/local.bsl").disk()));
            assertNull(entries.get("src/local.bsl").index());

            write(project, "src/generated/private.txt", "unsupported file\n");
            assertThrows(IOException.class, () -> ProjectSourceManifest.capture(access, true));
        }
    }

    @Test
    public void capturesGitDeltasAndWorkingFilesForOneProjectInAMonorepo() throws Exception
    {
        Path root = temp.newFolder("monorepo").toPath();
        Path project = root.resolve("Allowed");
        try (Git git = Git.init().setDirectory(root.toFile()).call())
        {
            git.getRepository().getConfig().setString("core", null, "autocrlf", "false");
            git.getRepository().getConfig().setString("core", null, "excludesfile", "global.ignore");
            git.getRepository().getConfig().save();
            write(root, "global.ignore", "global.bsl\n");
            write(root, ".gitignore", "Allowed/src/generated/\n");
            write(root, ".git/info/exclude", "local.bsl\n");
            write(project, "src/.gitignore", "cache*.bsl\n!cache-keep.bsl\n");
            write(project, "src/staged.mdo", "before\n");
            write(project, "src/both.bsl", "original\n");
            write(project, "src/deleted.bsl", "delete me\n");
            write(project, "src/old-a.bsl", "same\n");
            write(project, "src/old-b.bsl", "same\n");
            Path neighbor = Files.createDirectories(root.resolve("Neighbor/src"));
            // This sibling blob would exceed the capture limit if opened before applying project scope.
            Files.write(neighbor.resolve("large.bsl"), new byte[4 * 1024 * 1024 + 1]);
            git.add().addFilepattern("Allowed").addFilepattern("Neighbor").addFilepattern(".gitignore").call();
            String head = git.commit().setMessage("fixture").setAuthor("Fixture", "fixture@example.invalid")
                .setCommitter("Fixture", "fixture@example.invalid").call().name();

            write(project, "src/staged.mdo", "staged\n");
            write(project, "src/both.bsl", "index\n");
            write(project, "src/new-a.bsl", "same\n");
            write(project, "src/new-b.bsl", "same\n");
            git.add().addFilepattern("Allowed").call();
            git.rm().addFilepattern("Allowed/src/old-a.bsl").addFilepattern("Allowed/src/old-b.bsl").call();
            write(project, "src/both.bsl", "original\n");
            Files.delete(project.resolve("src/deleted.bsl"));
            write(project, "src/new.bsl", "untracked\n");
            write(project, "src/generated/ignored.bsl", "ignored by ancestor\n");
            write(project, "src/cache-drop.bsl", "ignored\n");
            write(project, "src/cache-keep.bsl", "explicitly included\n");
            write(project, "src/global.bsl", "ignored globally\n");
            write(project, "src/local.bsl", "ignored in info/exclude\n");
            write(project, "src/private.xml", "not in the policy\n");
            try (Git nested = Git.init().setDirectory(project.resolve("src/Nested").toFile()).call())
            {
                write(project, "src/Nested/hidden.bsl", "nested repository\n");
            }

            ProjectSourceManifest.Project snapshot = capture(project);
            assertEquals(head, snapshot.head());
            assertEquals(git.getRepository().getFullBranch(), snapshot.branch());
            Map<String, Entry> entries = snapshot.entries().stream()
                .collect(Collectors.toMap(Entry::path, Function.identity()));
            assertEquals(Set.of("src/both.bsl", "src/staged.mdo", "src/deleted.bsl", "src/new.bsl", "src/cache-keep.bsl",
                "src/old-a.bsl", "src/old-b.bsl", "src/new-a.bsl", "src/new-b.bsl"), entries.keySet());
            assertEquals(entries.keySet().stream().sorted().toList(), snapshot.entries().stream().map(Entry::path).toList());

            Entry staged = entries.get("src/staged.mdo");
            assertTrue(staged.staged());
            assertFalse(staged.unstaged());
            assertEquals(List.of("before\n", "staged\n", "staged\n"),
                List.of(text(staged.head()), text(staged.index()), text(staged.disk())));

            Entry both = entries.get("src/both.bsl");
            assertTrue(both.staged());
            assertTrue(both.unstaged());
            assertEquals(both.head().contentId(), both.disk().contentId());
            assertNotEquals(both.head().contentId(), both.index().contentId());
            assertEquals("index\n", text(both.index()));

            Entry deleted = entries.get("src/deleted.bsl");
            assertFalse(deleted.staged());
            assertTrue(deleted.unstaged());
            assertNull(deleted.disk());
            Entry untracked = entries.get("src/new.bsl");
            assertNull(untracked.head());
            assertNull(untracked.index());
            assertEquals("untracked\n", text(untracked.disk()));

            for (String suffix : List.of("a", "b"))
            {
                Entry removed = entries.get("src/old-" + suffix + ".bsl");
                Entry added = entries.get("src/new-" + suffix + ".bsl");
                assertTrue(removed.staged());
                assertTrue(added.staged());
                assertNotNull(removed.head());
                assertNull(removed.index());
                assertNull(added.head());
                assertEquals("same\n", text(added.index()));
            }
        }
    }

    @Test
    public void keepsRawDiskBytesWhenAttributesCouldNormalizeThem() throws Exception
    {
        Path project = temp.newFolder("normalization").toPath();
        try (Git git = Git.init().setDirectory(project.toFile()).call())
        {
            git.getRepository().getConfig().setString("core", null, "autocrlf", "false");
            git.getRepository().getConfig().save();
            write(project, "src/test.bsl", "content\n");
            git.add().addFilepattern("src").call();
            write(project, ".gitattributes", "*.bsl text\n");
            write(project, "src/test.bsl", "content\r\n");

            ProjectSourceManifest.Project snapshot = capture(project);
            assertTrue(snapshot.rawDiskComparison());
            assertEquals(1, snapshot.entries().size());
            Entry entry = snapshot.entries().get(0);
            assertEquals("content\n", text(entry.index()));
            assertEquals("content\r\n", text(entry.disk()));
        }
    }

    @Test
    public void rejectsAnUnstagedChangeBetweenCaptureAndConfirmation() throws Exception
    {
        Path project = temp.newFolder("changing").toPath();
        try (Git git = Git.init().setDirectory(project.toFile()).call())
        {
            write(project, "src/unchanged.mdo", "configuration\n");
            write(project, "src/module.bsl", "before\n");
            git.add().addFilepattern("src").call();
            AtomicBoolean changed = new AtomicBoolean();
            IOException failure = assertThrows(IOException.class, () -> ProjectSnapshotCapture.readStable(() -> {
                ProjectSourceManifest.Project observed = capture(project);
                if (changed.compareAndSet(false, true))
                {
                    write(project, "src/module.bsl", "after\n");
                }
                return List.of(observed);
            }, () -> new ProjectSnapshotCapture.ModelObservation(false, Map.of()), () -> false));
            assertTrue(failure.getMessage().contains("changed during capture"));
            assertEquals("after\n", Files.readString(project.resolve("src/module.bsl")));
            assertEquals("configuration\n", Files.readString(project.resolve("src/unchanged.mdo")));
        }
    }

    @Test
    public void rejectsAnIndirectGitConfigurationBeforeOpeningTheRepository() throws Exception
    {
        Path project = temp.newFolder("indirect-git").toPath();
        try (Git git = Git.init().setDirectory(project.toFile()).call())
        {
            write(project, "src/module.bsl", "fixture\n");
        }
        Path config = project.resolve(".git/config");
        Path external = Files.move(config, temp.newFolder("outside").toPath().resolve("config"));
        Files.createSymbolicLink(config, external);
        try
        {
            IOException failure = assertThrows(IOException.class, () -> capture(project));
            assertEquals(WindowsSourceTree.unavailable().getMessage(), failure.getMessage());
        }
        finally
        {
            Files.delete(config);
        }
    }

    private static ProjectSourceManifest.Project capture(Path project) throws IOException
    {
        Grant grant = new Grant("Allowed", project.toString(), Set.of("bsl", "mdo"));
        ScopedProject scope = new ScopedProject(mock(IProject.class, RETURNS_DEEP_STUBS), grant, project);
        return ProjectSourceManifest.capture(scope, WindowsSourceTree.load());
    }

    private static void write(Path root, String relative, String value) throws IOException
    {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, value, StandardCharsets.UTF_8);
    }

    private static String text(Version version)
    {
        return new String(version.bytes(), StandardCharsets.UTF_8);
    }
}
