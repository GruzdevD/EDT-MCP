/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils.snapshot;

import static org.junit.Assert.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class ReviewEvidenceStoreTest
{
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private final Clock start = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"),ZoneOffset.UTC);
    private final ReviewEvidenceStore.Cycle cycle = new ReviewEvidenceStore.Cycle("app","Base","refs/heads/main","change","one",List.of(),Map.of());

    private UUID reserve(ReviewEvidenceStore store) throws IOException
    {
        byte[] bytes = {1,2,3};
        String digest = ProjectSourceManifest.contentIdOf(bytes);
        UUID id = UUID.randomUUID();
        store.reserve(id,"owner",cycle,null,List.of(),Map.of(),Map.of(),List.of(
            new ReviewEvidenceStore.File("production","Base","src/A.bsl",digest,bytes.length)),Map.of(digest,bytes));
        return id;
    }

    @Test public void firstFailureIsDurableAndImmutableAcrossReopen() throws Exception
    {
        Path root = temp.newFolder().toPath();
        var store = new ReviewEvidenceStore(root,start,ReviewEvidenceStore.QUOTA);
        UUID first = reserve(store);
        assertThrows(IOException.class,() -> reserve(store));
        assertFalse(store.finish(first,"PASSED","completed","green").baseline());
        UUID second = reserve(store);
        var red = store.finish(second,"FAILED","completed","red");
        assertTrue(red.baseline());
        var reopened = new ReviewEvidenceStore(root,start,ReviewEvidenceStore.QUOTA);
        assertEquals(red,reopened.find(second));
        assertEquals(red,reopened.finish(second,"PASSED","completed","other"));
        assertArrayEquals("red".getBytes(),reopened.read(second,red.reportDigest()));
        UUID broken = reserve(reopened);
        assertFalse(reopened.finish(broken,"BROKEN","export",null).baseline());
        assertNull(reopened.find(broken).reportDigest());
        var expired = new ReviewEvidenceStore(root,Clock.offset(start,ReviewEvidenceStore.RETENTION),ReviewEvidenceStore.QUOTA);
        assertThrows(IOException.class,() -> expired.find(second));
    }

    @Test public void brokenBaselineRetainsInputsWithoutInventingAReport() throws Exception
    {
        var store = new ReviewEvidenceStore(temp.newFolder().toPath(),start,ReviewEvidenceStore.QUOTA);
        UUID id = reserve(store);
        var broken = store.finish(id,"BROKEN","base configuration export",null);
        assertTrue(broken.baseline());
        assertNull(broken.reportDigest());
        assertEquals("base configuration export",store.find(id).phase());
        assertArrayEquals(new byte[]{1,2,3},store.read(id,broken.files().get(0).digest()));
    }

    @Test public void quotaAndUnknownNeverPublishSuccess() throws Exception
    {
        Path root = temp.newFolder().toPath();
        assertThrows(IOException.class,() -> reserve(new ReviewEvidenceStore(root,start,1)));
        var store = new ReviewEvidenceStore(root,start,ReviewEvidenceStore.QUOTA);
        UUID id = reserve(store);
        Path header = root.resolve("jobs").resolve(id+".json");
        Files.writeString(header,Files.readString(header).replace(ReviewEvidenceStore.INSTANCE.toString(),UUID.randomUUID().toString()));
        assertEquals("UNKNOWN",store.find(id).status());
        assertEquals("UNKNOWN",store.finish(id,"PASSED","completed","green").status());
        assertFalse(store.find(id).baseline());
    }

    @Test public void candidateCannotReserveEvidenceForAnotherChangeOnTheSameBranch() throws Exception
    {
        var base = new GitBuildInputs.Input("Base","app",cycle.branch(),"candidate","tree","digest");
        var identity = new ProjectSnapshotService.BuildIdentity("candidate",UUID.randomUUID(),"tree","manifest",List.of(base),cycle.changeId());
        ReviewEvidence.requireCycle(identity,cycle);
        var other = new ReviewEvidenceStore.Cycle("app","Base",cycle.branch(),"other",cycle.cycle(),List.of(),Map.of());
        assertThrows(IOException.class, () -> ReviewEvidence.requireCycle(identity,other));
    }

    @Test public void sameRepoExplicitPinsKeepTheirRevisionInBaselineComparison()
    {
        var base = new GitBuildInputs.Input("Base","app",cycle.branch(),"candidate","tree","digest");
        var local = new GitBuildInputs.Input("Local","app",cycle.branch(),"candidate","tree","digest");
        var pinned = new GitBuildInputs.Input("Pinned","app","refs/heads/tests","old-pin","tree2","digest2");
        var candidate = new ProjectSnapshotService.BuildIdentity("candidate",UUID.randomUUID(),"tree","manifest",List.of(base,local,pinned),"change");
        assertEquals(List.of("final","final","old-pin"),ReviewEvidence.comparisonInputs(candidate,"final").stream()
            .map(BuildReview.Dependency::commitSha).toList());
        var committed = new ProjectSnapshotService.BuildIdentity("commit",UUID.randomUUID(),"tree","manifest",List.of(
            new GitBuildInputs.Input("Base","app",cycle.branch(),"original","tree","digest"),pinned),null);
        assertEquals("old-pin",ReviewEvidence.comparisonInputs(committed,"final").get(1).commitSha());
    }

    @Test public void expiredCycleIsDistinctFromNeverSeenAfterPruningAndRestart() throws Exception
    {
        var root = temp.newFolder().toPath();
        var before = new ReviewEvidenceStore(root,start,ReviewEvidenceStore.QUOTA);
        UUID id = reserve(before);
        before.finish(id,"FAILED","completed","red");
        assertEquals(1,before.list(cycle).jobs().size());
        var later = Clock.offset(start,ReviewEvidenceStore.RETENTION);
        var expired = new ReviewEvidenceStore(root,later,ReviewEvidenceStore.QUOTA);
        assertThrows(IOException.class,() -> expired.find(id));
        var reopened = new ReviewEvidenceStore(root,later,ReviewEvidenceStore.QUOTA);
        assertThrows(IOException.class,() -> reopened.list(cycle));
        var unseen = new ReviewEvidenceStore.Cycle("app","Base",cycle.branch(),"change","unseen",List.of(),Map.of());
        assertTrue(reopened.list(unseen).jobs().isEmpty());
        assertFalse(reopened.list(unseen).expiredHistory());
        UUID next = reserve(reopened);
        assertFalse(reopened.finish(next,"FAILED","completed","red again").baseline());
        assertTrue(reopened.list(cycle).expiredHistory());
        assertEquals(1,reopened.list(cycle).jobs().size());
    }

}
