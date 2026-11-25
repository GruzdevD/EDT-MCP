/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils.snapshot;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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

    public static ToolResult capture(String name, List<String> extensions, boolean forYaxunit) throws IOException
    {
        long generation = GENERATION.get();
        Access access = SnapshotSourcePolicy.require(name, extensions);
        if (forYaxunit && access.projects().stream().anyMatch(project -> !project.grant().yaxunit()))
        {
            throw SnapshotSourcePolicy.denied();
        }
        if (!CAPTURES.tryAcquire())
        {
            throw new IOException("Snapshot capture is busy. Retry after the active captures finish.");
        }
        try
        {
            Snapshot snapshot = ProjectSnapshotCapture.capture(name, extensions, access, forYaxunit);
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

    static BuildAdmission authorizeBuild(UUID id) throws IOException
    {
        AuthenticatedClient client = McpProtocolHandler.currentAuthenticatedClient();
        if (client == null || !client.isCurrent())
        {
            throw SnapshotSourcePolicy.denied();
        }
        var lease = REGISTRY.acquire(client, id);
        boolean admitted = false;
        try
        {
            Retained retained = lease.retained();
            BuildAuthorization authorization = new BuildAuthorization(id, client, retained.scope());
            requireCurrentAccess(authorization.scope(), client, false);
            if (!retained.snapshot().yaxunitReady())
            {
                throw new IOException("The snapshot has no YAxUnit build inputs. Capture it with forYaxunit=true.");
            }
            authorization.verify();
            BuildAdmission admission = new BuildAdmission(authorization, lease);
            admitted = true;
            return admission;
        }
        finally
        {
            if (!admitted)
            {
                lease.close();
            }
        }
    }

    record BuildAuthorization(UUID id, AuthenticatedClient owner, List<Grant> scope)
    {
        boolean permitsCaller()
        {
            if (!owner.equals(McpProtocolHandler.currentAuthenticatedClient()))
            {
                return false;
            }
            try
            {
                verify();
                return true;
            }
            catch (IOException | RuntimeException | LinkageError e)
            {
                return false;
            }
        }

        void verify() throws IOException
        {
            requireCurrentAccess(scope, owner, true);
        }
    }

    record BuildAdmission(BuildAuthorization authorization, ProjectSnapshotRegistry<AuthenticatedClient>.Lease lease)
        implements AutoCloseable
    {
        BuildSource materialize(Path destination) throws IOException
        {
            try (lease)
            {
                authorization.verify();
                Snapshot snapshot = lease.retained().snapshot();
                List<Path> roots = new ArrayList<>();
                for (Project project : snapshot.projects())
                {
                    Path root = Files.createDirectory(destination.resolve("project-" + roots.size()));
                    roots.add(root);
                    for (var descriptor : project.buildInputs().files().entrySet())
                    {
                        write(root, descriptor.getKey(), descriptor.getValue());
                    }
                    for (var entry : project.entries())
                    {
                        if (entry.disk() != null)
                        {
                            write(root, entry.path(), entry.disk());
                        }
                    }
                }
                return new BuildSource(List.copyOf(roots), snapshot.sourceFingerprints());
            }
        }

        @Override
        public void close()
        {
            lease.close();
        }
    }

    record BuildSource(List<Path> projects, Map<String, String> fingerprints) { }

    private static void write(Path root, String relative, ProjectSourceManifest.Version version) throws IOException
    {
        if (Thread.currentThread().isInterrupted())
        {
            throw new IOException("The snapshot build was interrupted.");
        }
        Path file = root.resolve(relative).normalize();
        if (file.equals(root) || !file.startsWith(root))
        {
            throw SnapshotBuildInputs.unsupported();
        }
        Files.createDirectories(file.getParent());
        Files.write(file, version.bytes(), StandardOpenOption.CREATE_NEW);
    }

    private static void requireCurrentAccess(Retained retained, AuthenticatedClient client) throws IOException
    {
        requireCurrentAccess(retained.scope(), client, false);
    }

    private static void requireCurrentAccess(List<Grant> original, AuthenticatedClient client, boolean execution)
        throws IOException
    {
        Access current = SnapshotSourcePolicy.require(client, System.getProperty(SnapshotSourcePolicy.POLICY_PROPERTY),
            original.get(0).name(), original.subList(1, original.size()).stream().map(Grant::name).toList());
        SnapshotSourcePolicy.requireGrantScope(original, current.projects().stream().map(ScopedProject::grant).toList(),
            execution);
    }

    private static ToolResult describe(Retained retained)
    {
        Snapshot snapshot = retained.snapshot();
        return ToolResult.success().put("snapshotId", retained.id().toString())
            .put("expiresAt", retained.expiresAt().toString())
            .put("modelStatus", snapshot.modelStatus().name().toLowerCase(Locale.ROOT))
            .put("verified", snapshot.verified())
            .put("yaxunitReady", snapshot.yaxunitReady())
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
