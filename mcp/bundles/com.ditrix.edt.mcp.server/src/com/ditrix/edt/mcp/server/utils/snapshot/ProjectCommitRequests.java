/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils.snapshot;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.ditrix.edt.mcp.server.utils.snapshot.ProjectCommitPlan.Plan;
import com.ditrix.edt.mcp.server.utils.snapshot.ProjectSnapshotCapture.ModelStatus;
import com.ditrix.edt.mcp.server.utils.snapshot.ProjectSnapshotCapture.Snapshot;
import com.ditrix.edt.mcp.server.utils.snapshot.ProjectSnapshotRegistry.Retained;
import com.ditrix.edt.mcp.server.utils.snapshot.ProjectSourceManifest.Entry;
import com.ditrix.edt.mcp.server.utils.snapshot.ProjectSourceManifest.Version;
import com.ditrix.edt.mcp.server.utils.snapshot.SnapshotSourcePolicy.Grant;

/** Bounded, owner-scoped commit reviews. Retains fingerprints instead of source bytes. */
final class ProjectCommitRequests<Owner>
{
    private static final Duration TTL = Duration.ofMinutes(5);
    private static final int MAX_PLANS = 32;
    private final Clock clock;
    private final Map<UUID, OwnedReview<Owner>> reviews = new HashMap<>();

    ProjectCommitRequests(Clock clock)
    {
        this.clock = clock;
    }

    private record OwnedReview<Owner>(Owner owner, Review review) { }

    record FileVersion(UUID entryId, String projectName, String path, String gitPath, String headContentId,
        String indexContentId, String diskContentId, String renamePeer) { }

    record Review(UUID id, UUID snapshotId, Instant expiresAt, List<Grant> scope, List<Grant> selectedGrants, Path workTree,
        String branch, String parent, String tree, String message, ModelStatus modelStatus, List<FileVersion> files)
    {
        void requireCurrent(Snapshot snapshot) throws IOException
        {
            if (snapshot.modelStatus() == ModelStatus.DIVERGED)
            {
                throw new IOException("The EDT model diverged. Resolve the divergence and capture a new snapshot.");
            }
            Map<String, Map<String, Entry>> selected = new HashMap<>();
            Set<String> names = files.stream().map(FileVersion::projectName).collect(Collectors.toUnmodifiableSet());
            for (var project : snapshot.projects())
            {
                if (!names.contains(project.name()))
                {
                    continue;
                }
                if (!workTree.equals(project.workTree()) || !branch.equals(project.branch())
                    || !parent.equals(project.head()) || project.rawDiskComparison())
                {
                    throw ProjectCommitPlan.stale();
                }
                Map<String, Entry> entries = new HashMap<>();
                project.entries().forEach(entry -> entries.put(entry.path(), entry));
                selected.put(project.name(), entries);
            }
            for (FileVersion version : files)
            {
                Entry entry = selected.getOrDefault(version.projectName(), Map.of()).get(version.path());
                if (entry == null || !Objects.equals(version.headContentId(), contentId(entry.head()))
                    || !Objects.equals(version.indexContentId(), contentId(entry.index()))
                    || !Objects.equals(version.diskContentId(), contentId(entry.disk())))
                {
                    throw ProjectCommitPlan.stale();
                }
            }
        }

        List<String> selectedProjectNames()
        {
            return selectedGrants.stream().map(Grant::name).toList();
        }
    }

    @FunctionalInterface
    interface ReadGrants
    {
        List<Grant> read(Review review) throws IOException;
    }

    @FunctionalInterface
    interface Consent
    {
        void require(Review review) throws IOException;
    }

    @FunctionalInterface
    interface ReadSnapshot
    {
        Snapshot read(Review review) throws IOException;
    }

    synchronized Review retain(Owner owner, Retained snapshot, Plan plan) throws IOException
    {
        Instant now = clock.instant();
        reviews.values().removeIf(owned -> !now.isBefore(owned.review().expiresAt()));
        if (reviews.size() >= MAX_PLANS || !now.isBefore(snapshot.expiresAt()))
        {
            throw new IOException("No project commit plan slot is available. Retry after the active plans expire.");
        }
        var selection = plan.selection();
        Instant expire = now.plus(TTL);
        if (snapshot.expiresAt().isBefore(expire))
        {
            expire = snapshot.expiresAt();
        }
        List<Grant> scopes = selection.entries().stream().map(ProjectCommitPlan.SelectedEntry::grant).distinct().toList();
        List<FileVersion> files = selection.entries().stream().map(entry -> new FileVersion(entry.id(),
            entry.project().name(), entry.source().path(), entry.gitPath(), contentId(entry.source().head()),
            contentId(entry.source().index()), contentId(entry.source().disk()), selection.renamePeers().get(entry.id()))).toList();
        var review = new Review(UUID.randomUUID(), snapshot.id(), expire, snapshot.scope(), scopes, selection.workTree(),
            selection.branch(), selection.parent().name(), plan.tree().name(), plan.message(), selection.modelStatus(), files);
        reviews.put(review.id(), new OwnedReview<>(owner, review));
        return review;
    }

    synchronized Review require(Owner owner, UUID planId) throws IOException
    {
        var entry = reviews.get(planId);
        if (entry == null || owner == null || !owner.equals(entry.owner()) || !clock.instant().isBefore(entry.review().expiresAt()))
        {
            throw new IOException("The commit plan is unavailable. Request a new preview.");
        }
        return entry.review();
    }

    Review authorize(Owner owner, UUID planId, ReadGrants grants, Consent consent, ReadSnapshot snapshot) throws IOException
    {
        Review review = require(owner, planId);
        ProjectCommitPolicy.requireGrants(review.selectedGrants(), review.branch(), grants.read(review), true);
        consent.require(review);
        review.requireCurrent(snapshot.read(review));
        ProjectCommitPolicy.requireGrants(review.selectedGrants(), review.branch(), grants.read(review), true);
        return require(owner, planId);
    }

    synchronized void clear()
    {
        reviews.clear();
    }

    private static String contentId(Version version)
    {
        return version == null ? null : version.contentId();
    }
}
