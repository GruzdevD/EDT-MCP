/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils.snapshot;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import com.ditrix.edt.mcp.server.protocol.McpProtocolHandler;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.transport.HttpTransport.AuthenticatedClient;
import com.ditrix.edt.mcp.server.utils.snapshot.ProjectSnapshotCapture.Snapshot;
import com.ditrix.edt.mcp.server.utils.snapshot.ProjectSnapshotRegistry.Limits;
import com.ditrix.edt.mcp.server.utils.snapshot.ProjectSnapshotRegistry.Retained;
import com.ditrix.edt.mcp.server.utils.snapshot.ProjectSourceManifest.Project;
import com.ditrix.edt.mcp.server.utils.snapshot.SnapshotSourcePolicy.Access;
import com.ditrix.edt.mcp.server.utils.snapshot.SnapshotSourcePolicy.Grant;
import com.ditrix.edt.mcp.server.utils.snapshot.SnapshotSourcePolicy.ScopedProject;

/** The authenticated entry point shared by the capture and inspection tools. */
public final class ProjectSnapshotService
{
    public static final int MAX_PAGE_SIZE = ProjectSnapshotPages.MAX_PAGE_SIZE;
    private static final ProjectSnapshotRegistry<AuthenticatedClient> REGISTRY = new ProjectSnapshotRegistry<>(
        Clock.systemUTC(), new Limits(Duration.ofMinutes(10), 4, 16, 192L * 1024 * 1024));
    private static final ProjectSnapshotPages PAGES = new ProjectSnapshotPages();
    private static final Semaphore CAPTURES = new Semaphore(2);
    private static final AtomicLong GENERATION = new AtomicLong();
    private static ScheduledExecutorService cleaner;

    private ProjectSnapshotService()
    {
    }

    private record ProjectSummary(String projectName, String branch, String head, String sourceFingerprint,
        boolean rawDiskComparison) { }

    public static ToolResult capture(String name, List<String> extensions) throws IOException
    {
        long generation = GENERATION.get();
        Access access = SnapshotSourcePolicy.require(name, extensions);
        if (!CAPTURES.tryAcquire())
        {
            throw new IOException("Snapshot capture is busy. Retry after the active captures finish.");
        }
        try
        {
            Snapshot snapshot = ProjectSnapshotCapture.capture(name, extensions, access);
            List<Grant> scope = access.projects().stream().map(ScopedProject::grant).toList();
            Retained retained = retain(generation, access.client(), scope, snapshot);
            return describe(retained);
        }
        finally
        {
            CAPTURES.release();
        }
    }

    public static ToolResult inspect(UUID id, String view, UUID entryId, UUID compareTo, String cursor, int limit)
        throws IOException
    {
        AuthenticatedClient client = McpProtocolHandler.currentAuthenticatedClient();
        if (client == null || !client.isCurrent())
        {
            throw SnapshotSourcePolicy.denied();
        }
        try (var first = REGISTRY.acquire(client, id); var second = compareTo == null ? null : REGISTRY.acquire(client, compareTo))
        {
            Retained current = first.retained();
            Retained previous = second == null ? null : second.retained();
            requireCurrentAccess(current, client);
            if (previous != null)
            {
                requireCurrentAccess(previous, client);
            }
            ProjectSnapshotPages.View type = "manifest".equals(view) ? ProjectSnapshotPages.View.MANIFEST
                : ProjectSnapshotPages.View.DIFF;
            ProjectSnapshotPages.Page page = PAGES.read(current, previous, type, entryId, cursor, limit);
            requireCurrentAccess(current, client);
            if (previous != null)
            {
                requireCurrentAccess(previous, client);
            }
            ToolResult result = describe(current).put("view", view).put("total", page.total()).put("entries", page.entries());
            if (page.nextCursor() != null)
            {
                result.put("nextCursor", page.nextCursor());
            }
            if (previous != null)
            {
                result.put("compareTo", previous.id().toString());
            }
            return result;
        }
    }

    private static void requireCurrentAccess(Retained retained, AuthenticatedClient client) throws IOException
    {
        List<Grant> original = retained.scope();
        Access current = SnapshotSourcePolicy.require(client, System.getProperty(SnapshotSourcePolicy.POLICY_PROPERTY),
            original.get(0).name(), original.subList(1, original.size()).stream().map(Grant::name).toList());
        if (!original.equals(current.projects().stream().map(ScopedProject::grant).toList()))
        {
            throw SnapshotSourcePolicy.denied();
        }
    }

    private static ToolResult describe(Retained retained)
    {
        Snapshot snapshot = retained.snapshot();
        return ToolResult.success().put("snapshotId", retained.id().toString())
            .put("expiresAt", retained.expiresAt().toString())
            .put("modelStatus", snapshot.modelStatus().name().toLowerCase(Locale.ROOT))
            .put("verified", snapshot.verified())
            .put("projects", snapshot.projects().stream().map(project -> summary(project, snapshot)).toList());
    }

    private static ProjectSummary summary(Project project, Snapshot snapshot)
    {
        return new ProjectSummary(project.name(), project.branch(), project.head(),
            snapshot.sourceFingerprints().get(project.name()), project.rawDiskComparison());
    }

    private static synchronized Retained retain(long generation, AuthenticatedClient client, List<Grant> scope,
        Snapshot snapshot) throws IOException
    {
        if (generation != GENERATION.get())
        {
            throw ProjectSnapshotRegistry.unavailable();
        }
        if (cleaner == null)
        {
            cleaner = Executors.newSingleThreadScheduledExecutor(task -> {
                Thread worker = new Thread(task, "EDT-MCP snapshot expiry");
                worker.setDaemon(true);
                return worker;
            });
            cleaner.scheduleWithFixedDelay(REGISTRY::expire, 30, 30, TimeUnit.SECONDS);
        }
        return REGISTRY.retain(client, scope, snapshot);
    }

    /** Drops retained snapshots when the protected listener stops or restarts. */
    public static synchronized void invalidate()
    {
        GENERATION.incrementAndGet();
        REGISTRY.invalidateAll();
        if (cleaner != null)
        {
            cleaner.shutdownNow();
            cleaner = null;
        }
    }
}
