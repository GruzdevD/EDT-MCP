/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils.snapshot;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import com.ditrix.edt.mcp.server.utils.snapshot.ProjectSnapshotCapture.Snapshot;
import com.ditrix.edt.mcp.server.utils.snapshot.ProjectSourceManifest.Entry;
import com.ditrix.edt.mcp.server.utils.snapshot.ProjectSourceManifest.Project;
import com.ditrix.edt.mcp.server.utils.snapshot.ProjectSourceManifest.Version;
import com.ditrix.edt.mcp.server.utils.snapshot.SnapshotSourcePolicy.Grant;

/** Bounded retention for authenticated owners. A lease keeps an in-progress read alive through expiry. */
final class ProjectSnapshotRegistry<Owner>
{
    record Retained(UUID id, Instant expiresAt, List<Grant> scope, Snapshot snapshot)
    {
        Retained
        {
            scope = List.copyOf(scope);
        }
    }

    record Limits(Duration ttl, int perOwner, int total, long bytes)
    {
        Limits
        {
            if (ttl.isNegative() || ttl.isZero() || perOwner < 1 || total < perOwner || bytes < 1)
            {
                throw new IllegalArgumentException("Invalid snapshot retention limits.");
            }
        }
    }

    private final Clock clock;
    private final Limits limits;
    private final Map<UUID, Slot> slots = new HashMap<>();
    private long bytes;

    ProjectSnapshotRegistry(Clock clock, Limits limits)
    {
        this.clock = Objects.requireNonNull(clock);
        this.limits = Objects.requireNonNull(limits);
    }

    synchronized Retained retain(Owner owner, List<Grant> scope, Snapshot snapshot) throws IOException
    {
        Objects.requireNonNull(owner);
        Instant now = clock.instant();
        purge(now);
        long size = retainedSize(scope, snapshot);
        long ownerCount = slots.values().stream().filter(slot -> owner.equals(slot.owner)).count();
        if (slots.size() >= limits.total() || ownerCount >= limits.perOwner() || size > limits.bytes() - bytes)
        {
            throw new IOException("Snapshot storage quota exceeded. Wait for retained snapshots to expire or reduce the scope.");
        }
        UUID id;
        do
        {
            id = UUID.randomUUID();
        }
        while (slots.containsKey(id));
        Retained retained = new Retained(id, now.plus(limits.ttl()), scope, snapshot);
        slots.put(id, new Slot(owner, retained, size));
        bytes += size;
        return retained;
    }

    synchronized Lease acquire(Owner owner, UUID id) throws IOException
    {
        Instant now = clock.instant();
        purge(now);
        Slot slot = slots.get(id);
        if (slot == null || slot.unavailable(now) || !slot.owner.equals(owner))
        {
            throw unavailable();
        }
        slot.readers++;
        return new Lease(slot);
    }

    static IOException unavailable()
    {
        return new IOException("Snapshot is unavailable or has expired. Capture a new snapshot and retry.");
    }

    synchronized void expire()
    {
        purge(clock.instant());
    }

    synchronized void invalidateAll()
    {
        for (Slot slot : slots.values())
        {
            slot.invalidated = true;
        }
        purge(clock.instant());
    }

    private void purge(Instant now)
    {
        Iterator<Slot> all = slots.values().iterator();
        while (all.hasNext())
        {
            Slot slot = all.next();
            if (slot.readers == 0 && slot.unavailable(now))
            {
                bytes -= slot.size;
                all.remove();
            }
        }
    }

    private static long retainedSize(List<Grant> scope, Snapshot snapshot)
    {
        long size = 1024;
        for (Grant grant : scope)
        {
            size += 128L + 2L * (grant.name().length() + grant.location().length());
        }
        for (Project project : snapshot.projects())
        {
            size += 512;
            for (Entry entry : project.entries())
            {
                size += 256 + 2L * entry.path().length() + size(entry.head()) + size(entry.index()) + size(entry.disk());
            }
        }
        return size;
    }

    private static long size(Version version)
    {
        return version == null ? 0 : 160L + version.size();
    }

    private final class Slot
    {
        private final Owner owner;
        private final Retained retained;
        private final long size;
        private int readers;
        private boolean invalidated;

        Slot(Owner owner, Retained retained, long size)
        {
            this.owner = owner;
            this.retained = retained;
            this.size = size;
        }

        boolean unavailable(Instant now)
        {
            return invalidated || !now.isBefore(retained.expiresAt());
        }
    }

    final class Lease implements AutoCloseable
    {
        private Slot slot;

        private Lease(Slot slot)
        {
            this.slot = slot;
        }

        Retained retained()
        {
            synchronized (ProjectSnapshotRegistry.this)
            {
                if (slot == null)
                {
                    throw new IllegalStateException("Snapshot lease is closed.");
                }
                return slot.retained;
            }
        }

        @Override
        public void close()
        {
            synchronized (ProjectSnapshotRegistry.this)
            {
                if (slot != null)
                {
                    slot.readers--;
                    slot = null;
                    purge(clock.instant());
                }
            }
        }
    }
}
