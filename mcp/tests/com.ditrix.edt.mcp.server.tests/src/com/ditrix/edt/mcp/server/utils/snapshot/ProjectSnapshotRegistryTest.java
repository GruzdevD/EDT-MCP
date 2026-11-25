/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils.snapshot;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.Test;

import com.ditrix.edt.mcp.server.utils.BackgroundJobs;
import com.ditrix.edt.mcp.server.utils.BackgroundJobs.Status;
import com.ditrix.edt.mcp.server.utils.snapshot.ProjectSnapshotCapture.ModelStatus;
import com.ditrix.edt.mcp.server.utils.snapshot.ProjectSnapshotCapture.Snapshot;
import com.ditrix.edt.mcp.server.utils.snapshot.ProjectSnapshotRegistry.Limits;

public class ProjectSnapshotRegistryTest
{
    private static final Duration TTL = Duration.ofMinutes(1);
    private static final Snapshot EMPTY = new Snapshot(List.of(), Map.of(), ModelStatus.UNKNOWN);

    @Test
    public void expiredIdRejectsNewReadersWhileExistingLeasesKeepTheirData() throws IOException
    {
        TestClock clock = new TestClock();
        ProjectSnapshotRegistry<String> registry = new ProjectSnapshotRegistry<>(clock, new Limits(TTL, 1, 1, 2048));
        var retained = registry.retain("alice", List.of(), EMPTY);
        assertEquals(clock.instant().plus(TTL), retained.expiresAt());
        assertThrows(IOException.class, () -> registry.acquire("bob", retained.id()));
        try (var first = registry.acquire("alice", retained.id()); var second = registry.acquire("alice", retained.id()))
        {
            clock.advance(TTL);
            registry.expire();
            assertThrows(IOException.class, () -> registry.acquire("alice", retained.id()));
            assertThrows(IOException.class, () -> registry.retain("bob", List.of(), EMPTY));
            assertSame(EMPTY, first.retained().snapshot());
            first.close();
            first.close();
            assertSame(EMPTY, second.retained().snapshot());
            assertThrows(IOException.class, () -> registry.retain("bob", List.of(), EMPTY));
        }
        assertNotEquals(retained.id(), registry.retain("bob", List.of(), EMPTY).id());
    }

    @Test
    public void enforcesOwnerAndByteQuotasAndReclaimsUnleasedEntries() throws IOException
    {
        TestClock clock = new TestClock();
        ProjectSnapshotRegistry<String> registry = new ProjectSnapshotRegistry<>(clock, new Limits(TTL, 1, 4, 2048));
        registry.retain("alice", List.of(), EMPTY);
        assertThrows(IOException.class, () -> registry.retain("alice", List.of(), EMPTY));
        var second = registry.retain("bob", List.of(), EMPTY);
        assertThrows(IOException.class, () -> registry.retain("charlie", List.of(), EMPTY));

        registry.invalidateAll();
        assertThrows(IOException.class, () -> registry.acquire("bob", second.id()));
        registry.retain("charlie", List.of(), EMPTY);
        clock.advance(TTL);
        registry.expire();
        registry.retain("alice", List.of(), EMPTY);
        registry.retain("bob", List.of(), EMPTY);
    }

    @Test
    public void admittedJobCanReadAfterTtlAndQueuedCancellationReclaimsTheLease() throws Exception
    {
        for (boolean cancel : List.of(false, true))
        {
            TestClock clock = new TestClock();
            var registry = new ProjectSnapshotRegistry<String>(clock, new Limits(TTL, 1, 1, 2048));
            var retained = registry.retain("alice", List.of(), EMPTY);
            CountDownLatch occupyingWorker = new CountDownLatch(1);
            CountDownLatch unblock = new CountDownLatch(1);
            try (var jobs = new BackgroundJobs(4, 1); var lease = registry.acquire("alice", retained.id()))
            {
                jobs.start("occupying", 10_000, "starting", progress -> {
                    occupyingWorker.countDown();
                    unblock.await();
                    return "finished";
                });
                assertTrue(occupyingWorker.await(3, TimeUnit.SECONDS));
                var job = jobs.startProtected("snapshot", 10_000, 2, "queued", () -> true, lease::close,
                    progress -> lease.retained().snapshot());
                clock.advance(TTL);
                assertThrows(IOException.class, () -> registry.acquire("alice", retained.id()));
                assertThrows(IOException.class, () -> registry.retain("bob", List.of(), EMPTY));
                if (cancel)
                {
                    jobs.cancel(job.getId());
                    assertEquals(Status.CANCELLED, jobs.await(job.getId(), 3_000).getStatus());
                    assertNotEquals(retained.id(), registry.retain("bob", List.of(), EMPTY).id());
                }
                unblock.countDown();
                if (!cancel)
                {
                    var result = jobs.await(job.getId(), 3_000);
                    assertEquals(Status.DONE, result.getStatus());
                    assertSame(EMPTY, result.getResult());
                    jobs.close();
                    assertNotEquals(retained.id(), registry.retain("bob", List.of(), EMPTY).id());
                }
            }
        }
    }

    private static final class TestClock extends Clock
    {
        private Instant now = Instant.parse("2025-01-01T00:00:00Z");

        void advance(Duration elapsed)
        {
            now = now.plus(elapsed);
        }

        @Override
        public Instant instant()
        {
            return now;
        }

        @Override
        public ZoneId getZone()
        {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone)
        {
            return Clock.fixed(now, zone);
        }
    }
}
