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

import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.McpProtocolHandler;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.transport.HttpTransport.AuthenticatedClient;
import com.ditrix.edt.mcp.server.utils.ConsentPreview;
import com.ditrix.edt.mcp.server.utils.DestructiveConsentGate;
import com.ditrix.edt.mcp.server.utils.snapshot.ProjectCommitRequests.Review;
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
    private static final ProjectCommitRequests<AuthenticatedClient> COMMITS = new ProjectCommitRequests<>(Clock.systemUTC());
    private static final String CONFIRM_COMMIT_TOOL = "confirm_project_commit";
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

    public static ToolResult inspectCandidate(UUID planId, String treeId, ProjectVersionReader.Query query) throws IOException
    {
        var caller = currentClient();
        var review = COMMITS.require(caller,planId);
        if (review.candidate() == null || !review.tree().equals(treeId)) { throw SnapshotSourcePolicy.denied(); }
        var inputs = review.candidate();
        int index = -1;
        for (int i = 0; i < inputs.inputs().size(); i++)
        {
            var input = inputs.inputs().get(i);
            if (input.projectName().equals(query.projectName()) && input.repoId().equals(query.repoId())) { index = i; break; }
        }
        if (index < 0) { throw SnapshotSourcePolicy.denied(); }
        var project = inputs.snapshot().projects().get(index);
        var current = requireCurrentAccess(inputs.scope(),caller,false);
        requireReviewGrant(grants(current),query.repoId(),project);
        List<ProjectSourceManifest.Entry> entries;
        if ("diff".equals(query.view()))
        {
            try (var lease = REGISTRY.acquire(caller, review.snapshotId()))
            {
                var paths = review.files().stream().filter(f -> project.name().equals(f.projectName()) && !review.isArtifact(f))
                    .map(ProjectCommitRequests.FileVersion::path).collect(java.util.stream.Collectors.toSet());
                entries = candidateDiff(lease.retained().snapshot(), project.name(), paths);
            }
        }
        else
        {
            entries = new ArrayList<>(project.entries());
            project.buildInputs().files().forEach((path,file) -> entries.add(new ProjectSourceManifest.Entry(path,null,null,file)));
        }
        var readable = new Project(project.name(),project.branch(),project.head(),entries,false);
        var result = ProjectVersionReader.read(PAGES,query,readable,treeId);
        requireReviewGrant(grants(requireCurrentAccess(inputs.scope(),caller,false)),query.repoId(),project);
        return result.put("treeId",treeId).put("manifestId",inputs.manifestId()).put("expiresAt",review.expiresAt().toString());
    }

    static List<ProjectSourceManifest.Entry> candidateDiff(Snapshot captured, String projectName, java.util.Set<String> selected)
    {
        return captured.projects().stream().filter(p -> p.name().equals(projectName)).flatMap(p -> p.entries().stream())
            .filter(e -> selected.contains(e.path())).toList();
    }

    public static ToolResult inspectVersion(ProjectVersionReader.Query query) throws IOException
    {
        if (query.commitSha() != null)
        {
            return ProjectCommitVersion.inspect(PAGES, query);
        }
        if (query.snapshotId() == null || query.baseSha() != null || query.branch() != null
            || !List.of("head","index","disk").contains(query.side()))
        {
            throw new IOException("Expected one snapshot side or one branch-scoped commit.");
        }
        AuthenticatedClient client = currentClient();
        try (var lease = REGISTRY.acquire(client, query.snapshotId()))
        {
            Retained retained = lease.retained();
            Project project = retained.snapshot().projects().stream()
                .filter(item -> item.name().equals(query.projectName())).findFirst()
                .orElseThrow(SnapshotSourcePolicy::denied);
            requireVersionAccess(retained, client, query.repoId(), project);
            ToolResult result = ProjectVersionReader.read(PAGES, query, project);
            requireVersionAccess(retained, client, query.repoId(), project);
            return result.put("expiresAt", retained.expiresAt().toString());
        }
    }

    private static void requireVersionAccess(Retained retained, AuthenticatedClient client, String repoId,
        Project project) throws IOException
    {
        Access current = requireCurrentAccess(retained.scope(), client, false);
        var original = retained.scope().stream().filter(g -> g.name().equals(project.name())).findFirst().orElseThrow();
        var now = grants(current).stream().filter(g -> g.name().equals(project.name())).findFirst().orElseThrow();
        if (!java.util.Objects.equals(original.review(),now.review())) { throw SnapshotSourcePolicy.denied(); }
        requireReviewGrant(retained.scope(), repoId, project);
        requireReviewGrant(grants(current), repoId, project);
    }

    static void requireReviewGrant(List<Grant> grants, String repoId, Project project) throws IOException
    {
        ReviewScope review = grants.stream().filter(grant -> grant.name().equals(project.name()))
            .map(Grant::review).filter(java.util.Objects::nonNull).findFirst().orElseThrow(SnapshotSourcePolicy::denied);
        review.require(repoId, project.workTree(), project.branch());
    }

    public static ToolResult stage(UUID snapshotId, String repoId, List<UUID> entryIds) throws IOException
    {
        var caller = currentClient();
        try (var lease = REGISTRY.acquire(caller, snapshotId))
        {
            var retained = lease.retained();
            var selection = ProjectCommitPlan.select(retained, entryIds);
            var access = requireCurrentAccess(retained.scope(), caller, false);
            requireStage(selection, retained.scope(), repoId);
            requireStage(selection, grants(access), repoId);
            var entries = ProjectSourceManifest.withCommitRepository(access.tree(), selection.workTree(), (repo, release) -> {
                var plan = ProjectCommitPlan.build(repo, selection, "Stage verified sources");
                return ProjectCommitExecution.stage(repo, plan, release, () -> new ProjectCommitSources(access.tree(), plan.changes()),
                    () -> {
                        var current = requireCurrentAccess(retained.scope(), caller, false);
                        requireStage(selection, grants(current), repoId);
                        ProjectSnapshotCapture.requireNoObservedDivergence(current);
                    });
            });
            return ToolResult.success().put("repoId", repoId).put("snapshotId", snapshotId.toString())
                .put("parentSha", selection.parent().name()).put("branch", selection.branch()).put("entries", entries);
        }
    }

    private static void requireStage(ProjectCommitPlan.Selection selection, List<Grant> grants, String repoId) throws IOException
    {
        for (var project : selection.entries().stream().map(ProjectCommitPlan.SelectedEntry::project).distinct().toList())
        {
            requireReviewGrant(grants, repoId, project);
            if (grants.stream().noneMatch(g -> g.name().equals(project.name()) && g.review() != null
                && g.review().stageBranches().contains(selection.branch())))
            {
                throw SnapshotSourcePolicy.denied();
            }
        }
    }

    public static ToolResult previewCommit(UUID snapshotId, List<UUID> entryIds, String message) throws IOException
    {
        return previewCommit(snapshotId, entryIds, message, null);
    }

    public static ToolResult previewCommit(UUID snapshotId, List<UUID> entryIds, String message, BuildReview build) throws IOException
    {
        AuthenticatedClient client = currentClient();
        long generation = GENERATION.get();
        try (var lease = REGISTRY.acquire(client, snapshotId))
        {
            Retained snapshot = lease.retained();
            Access access = requireCurrentAccess(snapshot.scope(), client, false);
            var selection = ProjectCommitPlan.select(snapshot, entryIds);
            record Preview(ProjectCommitPlan.Plan plan, GitBuildInputs.Contents inputs) { }
            var preview = ProjectSourceManifest.withCommitRepository(access.tree(), selection.workTree(), (repository, release) -> {
                var plan = ProjectCommitPolicy.preview(repository, build == null ? selection
                    : build.attach(selection, repository, access.tree(), snapshotId, grants(access), snapshot.snapshot().projects()), grants(access), message);
                return new Preview(plan, build == null ? null : GitBuildInputs.candidate(repository, plan, access, build));
            });
            Access current = requireCurrentAccess(snapshot.scope(), client, false);
            ProjectCommitPolicy.requireGrants(selection, grants(current), false);
            synchronized (ProjectSnapshotService.class)
            {
                if (generation != GENERATION.get())
                {
                    throw ProjectSnapshotRegistry.unavailable();
                }
                Review review = COMMITS.retain(client, snapshot, preview.plan(), build, preview.inputs());
                ToolResult result = ToolResult.success().put("planId", review.id().toString()).put("expiresAt", review.expiresAt().toString())
                    .put("modelStatus", review.modelStatus().name().toLowerCase(Locale.ROOT))
                    .put("branch", review.branch()).put("parentSha", review.parent()).put("treeId", review.tree())
                    .put("message", review.message()).put("entries", review.files());
                if (build != null)
                {
                    result.put("buildReview", build).put("candidateInputsId",review.id().toString())
                        .put("manifestId",review.candidate().manifestId()).put("inputs",review.candidate().inputs());
                }
                return result;
            }
        }
    }

    public static ToolResult commitError(IOException failure)
    {
        String message = failure.getMessage();
        if (failure instanceof java.nio.file.FileSystemException || message == null || message.indexOf('\\') >= 0
            || message.matches("(?s).*(?:[A-Za-z]:/|file:/| //).*"))
        {
            message = "The project commit could not access local Git state. Check the EDT server configuration.";
        }
        return ToolResult.error(message);
    }

    static Review authorizeCommit(UUID planId) throws IOException
    {
        var caller = currentClient();
        return COMMITS.authorize(caller, planId, review -> grants(requireCurrentAccess(review.scope(), caller, false)),
            ProjectSnapshotService::requireCommitConsent, review -> recaptureCommitSources(review, caller));
    }

    public static ToolResult confirmCommit(UUID planId) throws IOException
    {
        AuthenticatedClient caller = currentClient();
        Path journalPath = Activator.getDefault().getStateLocation().toFile().toPath().resolve("project-commits");
        try (var journal = ProjectCommitJournal.open(journalPath, Clock.systemUTC()))
        {
            String owner = caller.journalOwnerTag(journal.ownershipSecret(), planId);
            var previous = journal.find(planId, owner);
            if (previous != null)
            {
                return repeatCommit(journal, caller, planId, previous);
            }
            Review review = authorizeCommit(planId);
            var scope = new ProjectCommitJournal.Scope(review.workTree().toString(), review.scope(), review.selectedGrants(), review.build());
            var recorder = journal.begin(planId, owner, scope);
            try (var lease = REGISTRY.acquire(caller, review.snapshotId()))
            {
                Access access = requireCommitAccess(caller, scope, review.branch());
                var selection = ProjectCommitPlan.select(lease.retained(),
                    review.files().stream().filter(file -> !review.isArtifact(file)).map(ProjectCommitRequests.FileVersion::entryId).toList());
                var committed = ProjectSourceManifest.withCommitRepository(access.tree(), review.workTree(), (repository, release) -> {
                    var selectedInputs = review.build() == null ? selection
                        : review.build().attach(selection, repository, access.tree(), review.snapshotId(), grants(access), lease.retained().snapshot().projects());
                    var plan = ProjectCommitPlan.build(repository, selectedInputs, review.message());
                    for (var choice : selectedInputs.entries())
                    {
                        if (choice.artifact() && review.files().stream().noneMatch(f -> choice.id().equals(f.entryId())
                            && java.util.Objects.equals(f.indexContentId(), choice.source().index() == null
                                ? null : choice.source().index().contentId()))) { throw ProjectCommitPlan.stale(); }
                    }
                    if (!plan.tree().name().equals(review.tree()))
                    {
                        throw ProjectCommitPlan.stale();
                    }
                    return ProjectCommitExecution.commit(repository, plan, release,
                        () -> new ProjectCommitSources(access.tree(), plan.changes()), () -> {
                            var current = requireCommitAccess(caller, scope, review.branch());
                            var selected = current.projects().stream()
                                .filter(project -> review.selectedProjectNames().contains(project.grant().name())).toList();
                            ProjectSnapshotCapture.requireNoObservedDivergence(new Access(caller, current.tree(), selected));
                            COMMITS.require(caller, planId);
                        }, recorder);
                });
                return projectCommitResult(committed, true);
            }
            catch (IOException e)
            {
                var interrupted = journal.find(planId, owner);
                if (interrupted == null)
                {
                    throw e;
                }
                requireCommitAccess(caller, interrupted.scope(), interrupted.prepared().branch());
                return projectCommitResult(interrupted.prepared(), interrupted.phase() == ProjectCommitJournal.Phase.COMMITTED);
            }
        }
    }

    private static ToolResult repeatCommit(ProjectCommitJournal journal, AuthenticatedClient caller, UUID planId,
        ProjectCommitJournal.Receipt receipt) throws IOException
    {
        var prepared = receipt.prepared();
        var scope = receipt.scope();
        Access access = requireCommitAccess(caller, scope, prepared.branch());
        if (receipt.phase() == ProjectCommitJournal.Phase.COMMITTED)
        {
            return projectCommitResult(prepared, true);
        }
        try
        {
            var state = ProjectSourceManifest.withCommitRepository(access.tree(), Path.of(scope.workTree()),
                (repository, release) -> ProjectCommitExecution.recover(repository, prepared, release));
            if (state == ProjectCommitExecution.Recovery.COMMITTED)
            {
                journal.finish(planId, receipt);
                return projectCommitResult(prepared, true);
            }
        }
        catch (IOException e)
        {
            // The journal still identifies the operation. Report its SHA without attempting another commit.
        }
        return projectCommitResult(prepared, false);
    }

    private static Access requireCommitAccess(AuthenticatedClient caller, ProjectCommitJournal.Scope scope, String branch)
        throws IOException
    {
        Access access = requireCurrentAccess(scope.grants(), caller, false);
        ProjectCommitPolicy.requireGrants(scope.selected(), branch, grants(access), true);
        if (scope.build() != null) { scope.build().require(grants(access), branch, Path.of(scope.workTree())); }
        return access;
    }

    private static ToolResult projectCommitResult(ProjectCommitExecution.Prepared commit, boolean complete)
    {
        ToolResult result = complete ? ToolResult.success() : ToolResult.errorWithUnknownMutationOutcome(
            "The project commit needs recovery. Retry the same plan ID or ask the EDT administrator to check Git state.");
        return result.put("status", complete ? "committed" : "recovery_required").put("commitSha", commit.commit())
            .put("branch", commit.branch()).put("parentSha", commit.parent()).put("treeId", commit.tree());
    }

    private static void requireCommitConsent(Review review) throws IOException
    {
        List<String> names = review.files().stream().limit(10).map(ProjectCommitRequests.FileVersion::gitPath).toList();
        ConsentPreview summary = new ConsentPreview("Confirm project commit",
            review.branch() + " | parent " + review.parent() + " | tree " + review.tree(), review.files().size(), names);
        var decision = DestructiveConsentGate.getInstance().requireConsent(CONFIRM_COMMIT_TOOL, summary);
        if (decision != DestructiveConsentGate.ConsentDecision.ALLOW)
        {
            throw new IOException(DestructiveConsentGate.consentDeniedMessage(decision, CONFIRM_COMMIT_TOOL));
        }
    }

    private static Snapshot recaptureCommitSources(Review review, AuthenticatedClient caller) throws IOException
    {
        Access access = requireCurrentAccess(review.scope(), caller, false);
        ProjectCommitPolicy.requireGrants(review.selectedGrants(), review.branch(), grants(access), true);
        List<ScopedProject> selected = access.projects().stream()
            .filter(project -> review.selectedProjectNames().contains(project.grant().name())).toList();
        Access narrowed = new Access(caller, access.tree(), selected);
        if (!CAPTURES.tryAcquire())
        {
            throw new IOException("Snapshot capture is busy. Retry after the active captures finish.");
        }
        try
        {
            List<String> names = grants(narrowed).stream().map(Grant::name).toList();
            return ProjectSnapshotCapture.capture(names.get(0), names.subList(1, names.size()), narrowed, false);
        }
        finally
        {
            CAPTURES.release();
        }
    }

    private static AuthenticatedClient currentClient() throws IOException
    {
        AuthenticatedClient client = McpProtocolHandler.currentAuthenticatedClient();
        if (client == null || !client.isCurrent())
        {
            throw SnapshotSourcePolicy.denied();
        }
        return client;
    }

    static BuildAdmission authorizeCandidate(UUID id) throws IOException
    {
        var client = currentClient();
        var review = COMMITS.require(client,id);
        if (review.candidate() == null) { throw SnapshotSourcePolicy.denied(); }
        return admitVersion(id,client,review.candidate(),"candidate",review.tree(),review.build().changeId());
    }

    static BuildAdmission authorizeCommitRun(BuildReview.Dependency base, List<BuildReview.Dependency> other) throws IOException
    {
        var client = currentClient();
        var inputs = GitBuildInputs.committed(base,other);
        return admitVersion(UUID.randomUUID(),client,inputs,"commit",inputs.inputs().get(0).treeId(),null);
    }

    private static BuildAdmission admitVersion(UUID id, AuthenticatedClient client, GitBuildInputs.Contents contents,
        String kind, String treeId, String changeId) throws IOException
    {
        var auth = new BuildAuthorization(id,client,contents.scope(),contents.inputs());
        auth.verify();
        return new BuildAdmission(auth,null,contents.snapshot(),new BuildIdentity(kind,id,treeId,contents.manifestId(),contents.inputs(),changeId));
    }

    record BuildIdentity(String source, UUID id, String treeId, String manifestId, List<GitBuildInputs.Input> inputs, String changeId) { }

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
            BuildAuthorization authorization = new BuildAuthorization(id, client, retained.scope(), List.of());
            requireCurrentAccess(authorization.scope(), client, false);
            if (!retained.snapshot().yaxunitReady())
            {
                throw new IOException("The snapshot has no YAxUnit build inputs. Capture it with forYaxunit=true.");
            }
            authorization.verify();
            BuildAdmission admission = new BuildAdmission(authorization, lease, retained.snapshot(),null);
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

    record BuildAuthorization(UUID id, AuthenticatedClient owner, List<Grant> scope, List<GitBuildInputs.Input> inputs)
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
            var access = requireCurrentAccess(scope, owner, true);
            for (int i = 0; i < inputs.size(); i++)
            {
                var input = inputs.get(i);
                var current = access.projects().get(i).grant().review();
                if (current == null) { throw SnapshotSourcePolicy.denied(); }
                current.require(input.repoId(),Path.of(scope.get(i).review().workTree()),input.branch());
            }
        }
    }

    record BuildAdmission(BuildAuthorization authorization, ProjectSnapshotRegistry<AuthenticatedClient>.Lease lease,
        Snapshot snapshot, BuildIdentity identity)
        implements AutoCloseable
    {
        BuildSource materialize(Path destination) throws IOException
        {
            try (lease)
            {
                authorization.verify();
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
            if (lease != null) { lease.close(); }
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

    static Access requireCurrentAccess(List<Grant> original, AuthenticatedClient client, boolean execution)
        throws IOException
    {
        Access current = SnapshotSourcePolicy.require(client, System.getProperty(SnapshotSourcePolicy.POLICY_PROPERTY),
            original.get(0).name(), original.subList(1, original.size()).stream().map(Grant::name).toList());
        SnapshotSourcePolicy.requireGrantScope(original, grants(current), execution);
        return current;
    }

    private static List<Grant> grants(Access access)
    {
        return access.projects().stream().map(ScopedProject::grant).toList();
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
        COMMITS.clear();
        if (cleaner != null)
        {
            cleaner.shutdownNow();
            cleaner = null;
        }
    }
}
