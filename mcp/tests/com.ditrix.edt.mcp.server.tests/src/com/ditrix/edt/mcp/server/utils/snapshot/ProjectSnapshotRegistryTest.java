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

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import org.junit.Test;

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
