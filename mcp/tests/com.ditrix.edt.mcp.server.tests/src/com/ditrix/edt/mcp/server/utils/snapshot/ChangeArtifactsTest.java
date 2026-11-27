/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils.snapshot;

import static org.junit.Assert.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.eclipse.jgit.api.Git;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import com.google.gson.JsonParser;

public class ChangeArtifactsTest
{
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void exclusiveTransactionRejectsRacingEditorAndRollsBackOnConflict() throws Exception
    {
        var folder = temp.newFolder("change").toPath();
        var file = Files.writeString(folder.resolve("tasks.md"), "first");
        org.junit.Assume.assumeTrue(System.getProperty("os.name").startsWith("Windows"));
        var tree = WindowsSourceTree.load();
        try (var parent = tree.openProject(folder.toString()))
        {
            assertThrows(IOException.class, () -> tree.replace(parent, "tasks.md", false, 1024, old -> {
                assertArrayEquals("first".getBytes(StandardCharsets.UTF_8), old);
                assertThrows(IOException.class, () -> Files.writeString(file, "foreign"));
                throw new IOException("revision conflict");
            }));
            assertEquals("first", Files.readString(file));
            tree.replace(parent, "tasks.md", false, 1024, old -> "accepted".getBytes(StandardCharsets.UTF_8));
            assertEquals("accepted", Files.readString(file));
        }
    }

    @Test
    public void concurrentCasHasOneWinnerAndLostAppendReceiptSurvivesLaterEvents() throws Exception
    {
        var folder = temp.newFolder("cas").toPath();
        org.junit.Assume.assumeTrue(System.getProperty("os.name").startsWith("Windows"));
        var tree = WindowsSourceTree.load();
        try (var git = Git.init().setDirectory(folder.getParent().toFile()).call())
        {
            var disk = new ChangeArtifacts.Disk(tree,git.getRepository(),folder);
            var q = new ChangeArtifacts.Query("repo","Base","refs/heads/main","feature",null);
            var tasks = new ChangeArtifacts.Key("tasks",null);
            byte[] initial = "- [ ] 1.1 Build".getBytes(StandardCharsets.UTF_8);
            Files.write(folder.resolve("tasks.md"), initial);
            String rev = ChangeArtifacts.revision(q,tasks,initial).revision();
            var request = new ChangeArtifactWriter.Request(q,tasks,"checkbox",rev,null,null);
            var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
            try
            {
                var go = new java.util.concurrent.CountDownLatch(1);
                java.util.concurrent.Callable<Boolean> writer = () -> {
                    go.await();
                    try
                    {
                        ChangeArtifactWriter.writeAt(disk,request,"- [x] 1.1 Build".getBytes(StandardCharsets.UTF_8));
                        return true;
                    }
                    catch (IOException e) { return false; }
                };
                var a = executor.submit(writer);
                var b = executor.submit(writer);
                go.countDown();
                assertNotEquals(a.get(), b.get());
            }
            finally { executor.shutdownNow(); }
            var log = new ChangeArtifacts.Key("iterationLog",null);
            var append = new ChangeArtifactWriter.Request(q,log,"append","absent",java.util.UUID.randomUUID().toString(),null);
            var receipt = ChangeArtifactWriter.writeAt(disk,append,initial).toJson();
            var later = new ChangeArtifactWriter.Request(q,log,"append",JsonParser.parseString(receipt).getAsJsonObject()
                .getAsJsonObject("artifact").get("revision").getAsString(),java.util.UUID.randomUUID().toString(),null);
            ChangeArtifactWriter.writeAt(disk,later,initial);
            var bytes = Files.readAllBytes(folder.resolve("iteration-log.md"));
            assertEquals(receipt, ChangeArtifactWriter.writeAt(new ChangeArtifacts.Disk(WindowsSourceTree.load(),git.getRepository(),folder),append,initial).toJson());
            assertThrows(IOException.class, () -> ChangeArtifactWriter.writeAt(disk,append,new byte[]{1}));
            assertArrayEquals(bytes, Files.readAllBytes(folder.resolve("iteration-log.md")));
            assertThrows(IOException.class, () -> ChangeArtifactWriter.permission(new ChangeArtifacts.Key("boundary","one"),"put"));
            assertThrows(IOException.class, () -> ChangeArtifactWriter.permission(new ChangeArtifacts.Key("designReview",null),"append"));
            assertEquals("write",ChangeArtifactWriter.permission(new ChangeArtifacts.Key("designReview",null),"put"));
        }
    }

    @Test
    public void workingAndCommittedRevisionsMatchNativeGitAndRejectExternalLinks() throws Exception
    {
        var root = temp.newFolder("repo").toPath();
        var change = Files.createDirectories(root.resolve("openspec/changes/feature"));
        var doc = change.resolve("design.md");
        byte[] original = "\ufeffДизайн\r\n".getBytes(StandardCharsets.UTF_8);
        Files.write(doc, original);
        Files.writeString(root.resolve(".gitattributes"), "*.md -text\n");
        try (var git = Git.init().setDirectory(root.toFile()).call())
        {
            git.add().addFilepattern(".").call();
            var commit = git.commit().setMessage("design").setAuthor("test","test@example.invalid")
                .setCommitter("test","test@example.invalid").call();
            var q = new ChangeArtifacts.Query("r","Base",git.getRepository().getFullBranch(),"feature",null);
            var key = new ChangeArtifacts.Key("design",null);
            var nativeGit = new ProcessBuilder("git","hash-object","--no-filters",doc.toString()).directory(root.toFile()).start();
            String expected = new String(nativeGit.getInputStream().readAllBytes(),StandardCharsets.US_ASCII).trim();
            assertEquals(0,nativeGit.waitFor());
            var disk = new ChangeArtifacts.Disk(WindowsSourceTree.load(),git.getRepository(),change);
            var r = JsonParser.parseString(ChangeArtifacts.result(q,key,null,disk).toJson()).getAsJsonObject();
            assertEquals(expected,r.getAsJsonObject("artifact").get("gitBlobId").getAsString());
            String first = r.getAsJsonObject("artifact").get("revision").getAsString();
            Files.writeString(doc,"later");
            assertThrows(IOException.class, () -> ChangeArtifacts.result(q,key,first,disk));
            var old = new ChangeArtifacts.Committed(git.getRepository(),commit,ChangeArtifacts.prefix("feature"));
            assertArrayEquals(original,old.bytes("design.md"));
            var another = new ChangeArtifacts.Query("r","Base",q.branch(),"different",null);
            assertNotEquals(first, ChangeArtifacts.revision(another,key,original).revision());
            assertThrows(IOException.class, () -> new ChangeArtifacts.Key(".openspec.yaml",null).relative());
            Files.delete(doc);
            Files.createLink(doc,Files.writeString(temp.newFile().toPath(),"external"));
            assertThrows(IOException.class, () -> disk.bytes("design.md"));
        }
    }

    @Test public void caseVariantGitlinksInIndexAndHeadProtectDocuments() throws Exception
    {
        var tree = WindowsSourceTree.load();
        var root = temp.newFolder().toPath();
        var change = Files.createDirectories(root.resolve("openspec/changes/rate"));
        var doc = Files.writeString(change.resolve("tasks.md"),"original");
        var q = new ChangeArtifacts.Query("repo","Base","refs/heads/main","rate",null);
        var key = new ChangeArtifacts.Key("tasks",null);
        var req = new ChangeArtifactWriter.Request(q,key,"put",ChangeArtifacts.revision(q,key,Files.readAllBytes(doc)).revision(),null,null);
        try (var git = Git.init().setDirectory(root.toFile()).call())
        {
            var first = git.commit().setAllowEmpty(true).setMessage("parent").setAuthor("Test","test@example.test").setCommitter("Test","test@example.test").call();
            var repo = git.getRepository();
            var index = repo.lockDirCache();
            var builder = index.builder();
            var link = new org.eclipse.jgit.dircache.DirCacheEntry("OpenSpec/Changes/Rate");
            link.setFileMode(org.eclipse.jgit.lib.FileMode.GITLINK);
            link.setObjectId(first);
            builder.add(link);
            assertTrue(builder.commit());
            var disk = new ChangeArtifacts.Disk(tree,repo,change);
            for (int i = 0; i < 2; i++)
            {
                assertThrows(IOException.class,() -> disk.bytes(key.relative()));
                assertThrows(IOException.class,() -> ChangeArtifactWriter.writeAt(disk,req,new byte[]{1}));
                assertEquals("original",Files.readString(doc));
                if (i == 0)
                {
                    git.commit().setMessage("track gitlink").setAuthor("Test","test@example.test").setCommitter("Test","test@example.test").call();
                    assertTrue(repo.lockDirCache().builder().commit());
                }
            }
        }
    }

    @Test public void nestedChangeAndDeltaCheckoutsAreOutsideReadAndWriteScope() throws Exception
    {
        org.junit.Assume.assumeTrue(System.getProperty("os.name").startsWith("Windows"));
        var tree = WindowsSourceTree.load();
        for (boolean gitfile : new boolean[] {false,true})
        {
            var root = temp.newFolder().toPath();
            var change = Files.createDirectories(root.resolve("openspec/changes/rate"));
            var nested = gitfile ? Files.createDirectories(change.resolve("specs/calc")) : change.getParent();
            var key = gitfile ? new ChangeArtifacts.Key("delta","calc") : new ChangeArtifacts.Key("tasks",null);
            var path = change.resolve(key.relative());
            Files.writeString(path,"original");
            var q = new ChangeArtifacts.Query("repo","Base","refs/heads/main","rate",null);
            String revision = ChangeArtifacts.revision(q,key,Files.readAllBytes(path)).revision();
            try (var git = Git.init().setDirectory(root.toFile()).call())
            {
                if (gitfile) { Files.writeString(nested.resolve(".git"),"gitdir: elsewhere"); }
                else { Files.createDirectory(nested.resolve(".git")); }
                var disk = new ChangeArtifacts.Disk(tree,git.getRepository(),change);
                assertThrows(IOException.class, () -> disk.bytes(key.relative()));
                var req = new ChangeArtifactWriter.Request(q,key,"put",revision,null,null);
                assertThrows(IOException.class, () -> ChangeArtifactWriter.writeAt(disk,req,"updated".getBytes(StandardCharsets.UTF_8)));
                assertEquals("original",Files.readString(path));
            }
        }
    }

}
