/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils.snapshot;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.GsonProvider;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.transport.HttpTransport.AuthenticatedClient;
import com.ditrix.edt.mcp.server.utils.snapshot.ProjectSnapshotService.BuildAdmission;
import com.ditrix.edt.mcp.server.utils.snapshot.ReviewEvidenceStore.Cycle;
import com.ditrix.edt.mcp.server.utils.snapshot.ReviewEvidenceStore.File;
import com.ditrix.edt.mcp.server.utils.snapshot.ReviewEvidenceStore.Job;

public final class ReviewEvidence
{
    private static final ProjectSnapshotPages CURSORS = new ProjectSnapshotPages();
    private ReviewEvidence() { }

    static ReviewEvidenceStore store()
    {
        return new ReviewEvidenceStore(state().resolve("review-evidence"),Clock.systemUTC(),ReviewEvidenceStore.QUOTA);
    }

    static Path state() { return Activator.getDefault().getStateLocation().toFile().toPath(); }

    private static String owner(AuthenticatedClient client,UUID id) throws IOException
    {
        try (var journal = ProjectCommitJournal.open(state().resolve("project-commits"),Clock.systemUTC()))
        {
            return client.journalOwnerTag(journal.ownershipSecret(),id);
        }
    }

    static Job reserve(UUID id,BuildAdmission admission,String specification,Map<String,String> env,Map<String,List<String>> selectors)
        throws IOException
    {
        if (specification.length() > 32768 || admission.identity() == null) { throw SnapshotSourcePolicy.denied(); }
        Cycle cycle = GsonProvider.fromJson(specification,Cycle.class);
        if (cycle == null || !ChangeArtifacts.validId(cycle.cycle()) || !ChangeArtifacts.validId(cycle.changeId())
            || cycle.revisions() == null || cycle.testProjects() == null) { throw SnapshotSourcePolicy.denied(); }
        requireCycle(admission.identity(),cycle);
        authorize(cycle);
        var materials = new ReviewMaterials();
        materials.sources(admission.snapshot(),cycle.testProjects());
        materials.documents(cycle,null,cycle.revisions());
        admission.authorization().verify();
        return store().reserve(id,owner(admission.authorization().owner(),id),cycle,admission.identity(),
            admission.authorization().scope(),env,selectors,materials.files,materials.payload);
    }

    static void requireCycle(ProjectSnapshotService.BuildIdentity identity, Cycle cycle) throws IOException
    {
        if ("candidate".equals(identity.source()) && !cycle.changeId().equals(identity.changeId()))
        {
            throw SnapshotSourcePolicy.denied();
        }
        var first = identity.inputs().get(0);
        if (!first.repoId().equals(cycle.repoId()) || !first.projectName().equals(cycle.projectName())
            || !first.branch().equals(cycle.branch())) { throw SnapshotSourcePolicy.denied(); }
    }

    private static AuthenticatedClient authorize(Cycle cycle) throws IOException
    {
        var access = SnapshotSourcePolicy.require(cycle.projectName(),List.of());
        var grant = access.projects().get(0).grant().review();
        if (grant == null || !grant.repoId().equals(cycle.repoId()) || !grant.branches().contains(cycle.branch()))
        {
            throw SnapshotSourcePolicy.denied();
        }
        ChangeArtifacts.require(grant,cycle.changeId(),"evidence");
        ChangeArtifacts.require(grant,cycle.changeId(),"read");
        return access.client();
    }

    private static void require(Job job,Cycle query) throws IOException
    {
        var client = authorize(query);
        if (!ReviewEvidenceStore.sameCycle(job.cycle(),query) || !job.cycle().projectName().equals(query.projectName())
            || !job.cycle().branch().equals(query.branch()) || !owner(client,job.id()).equals(job.owner())) { throw SnapshotSourcePolicy.denied(); }
        var names = job.grants().stream().map(SnapshotSourcePolicy.Grant::name).toList();
        var current = SnapshotSourcePolicy.require(names.get(0),names.subList(1,names.size()));
        SnapshotSourcePolicy.requireGrantScope(job.grants(),current.projects().stream().map(SnapshotSourcePolicy.ScopedProject::grant).toList(),false);
        for (int i=0;i<names.size();i++)
        {
            var grant = current.projects().get(i).grant().review();
            if (grant == null) { throw SnapshotSourcePolicy.denied(); }
            var input = job.source().inputs().get(i);
            grant.require(input.repoId(),Path.of(job.grants().get(i).review().workTree()),input.branch());
        }
    }

    public static ToolResult inspectBaseline(ProjectVersionReader.Query query) throws IOException
    {
        var store = store();
        var job = store.find(query.snapshotId());
        require(job,job.cycle());
        if (!job.baseline()) { throw SnapshotSourcePolicy.denied(); }
        var input = job.source().inputs().stream().filter(i -> i.repoId().equals(query.repoId())
            && i.projectName().equals(query.projectName())).findFirst().orElseThrow(SnapshotSourcePolicy::denied);
        var entries = store.sourceEntries(job.id(),query.projectName());
        var project = new ProjectSourceManifest.Project(input.projectName(),input.branch(),input.revision(),entries,false);
        var result = ProjectVersionReader.read(CURSORS,query,project,input.treeId());
        require(job,job.cycle());
        return result.put("treeId",input.treeId()).put("expiresAt",java.time.Instant.ofEpochMilli(job.retainUntil()).toString());
    }

    public record Request(String repoId,String projectName,String branch,String changeId,String cycle,String view,
        UUID jobId,String group,String digest,String targetSha,Map<String,String> documentRevisions,String cursor) { }

    public static ToolResult inspect(Request r) throws IOException
    {
        var cycle = new Cycle(r.repoId(),r.projectName(),r.branch(),r.changeId(),r.cycle(),List.of(),Map.of());
        var client = authorize(cycle);
        if ("receipts".equals(r.view()))
        {
            try (var journal = ProjectCommitJournal.open(state().resolve("project-commits"),Clock.systemUTC()))
            {
                return ToolResult.success().put("entries",journal.discover(r,client)).put("complete",true);
            }
        }
        if (!ChangeArtifacts.validId(r.cycle())) { throw SnapshotSourcePolicy.denied(); }
        var store = store();
        String binding = GsonProvider.toJson(Arrays.asList(r.repoId(),r.projectName(),r.branch(),r.changeId(),r.cycle(),r.view(),
            r.jobId(),r.group(),r.digest(),r.targetSha(),r.documentRevisions() == null ? null : new TreeMap<>(r.documentRevisions())));
        int start = r.cursor() == null || "list".equals(r.view()) ? 0 : CURSORS.decode(r.cursor(),binding);
        if ("list".equals(r.view()))
        {
            var listing = store.list(cycle);
            List<Object> rows = new ArrayList<>();
            for (Job job : listing.jobs())
            {
                require(job,cycle);
                rows.add(Map.of("jobId",job.id(),"baseline",job.baseline(),"outcome",job.status(),"retainUntil",
                    java.time.Instant.ofEpochMilli(job.retainUntil()).toString(),"phase",job.phase()));
            }
            // Bind continuation to this observed result set; mutation between pages requires a fresh list.
            String observed = GsonProvider.toJson(rows) + listing.expiredHistory();
            if (r.cursor()!=null) { start = CURSORS.decode(r.cursor(),binding+observed); }
            var result = page(rows,start,binding+observed);
            if (listing.expiredHistory()) { result.put("complete",false).put("reason","retention_expired"); }
            return result;
        }
        if (r.jobId() == null) { throw new IOException("Select a retained job or baseline."); }
        var job = store.find(r.jobId());
        require(job,cycle);
        ToolResult result;
        switch (r.view())
        {
            case "job" -> result = ToolResult.success().put("source",job.source()).put("jobId",job.id()).put("outcome",job.status())
                .put("baseline",job.baseline()).put("phase",job.phase()).put("reportDigest",job.reportDigest())
                .put("environment",job.environment()).put("selectors",job.selectors()).put("revisions",job.cycle().revisions());
            case "files" -> result = page(job.files().stream().filter(f -> f.group().equals(r.group())).toList(),start,binding);
            case "content" -> {
                if (r.digest()==null) { throw new IOException("Select a listed content digest."); }
                byte[] bytes = store.read(job.id(),r.digest());
                result = content(bytes,r.digest(),start,binding);
            }
            case "compare" -> result = compare(r,job,start,binding);
            default -> throw new IOException("Unknown evidence view.");
        }
        require(job,cycle);
        return result.put("retainUntil",java.time.Instant.ofEpochMilli(job.retainUntil()).toString());
    }

    static List<BuildReview.Dependency> comparisonInputs(ProjectSnapshotService.BuildIdentity source, String targetSha)
    {
        var pins = new ArrayList<BuildReview.Dependency>();
        for (var input : source.inputs())
        {
            // The first input is the base. Other commit-source dependencies have explicit pins,
            // even when their repository, branch and SHA happen to match the base.
            String revision = pins.isEmpty() || "candidate".equals(input.revision()) ? targetSha : input.revision();
            pins.add(new BuildReview.Dependency(input.repoId(), input.projectName(), input.branch(), revision));
        }
        return pins;
    }

    private static ToolResult compare(Request r, Job job,int start,String binding) throws IOException
    {
        if (!job.baseline() || !List.of("production","tests","documents").contains(r.group())
            || (r.documentRevisions() == null ? r.targetSha() == null || !org.eclipse.jgit.lib.ObjectId.isId(r.targetSha())
                : !"documents".equals(r.group()) || r.targetSha() != null)) { throw SnapshotSourcePolicy.denied(); }
        var target = new ReviewMaterials();
        if ("documents".equals(r.group()))
        {
            target.documents(job.cycle(),r.documentRevisions()==null ? r.targetSha() : null,r.documentRevisions());
        }
        else
        {
            var pins = comparisonInputs(job.source(), r.targetSha());
            var current = GitBuildInputs.committedForRead(pins.get(0),pins.subList(1,pins.size()));
            target.sources(current.snapshot(),job.cycle().testProjects());
        }
        if (r.digest() != null)
        {
            if (target.files.stream().noneMatch(f -> f.group().equals(r.group()) && f.digest().equals(r.digest())))
            { throw SnapshotSourcePolicy.denied(); }
            return content(target.payload.get(r.digest()),r.digest(),start,binding)
                .put("group",r.group()).put("targetSha",r.targetSha()).put("documentRevisions",r.documentRevisions());
        }
        record Pair(String projectName,String path,String before,String after) { }
        var left = new TreeMap<String,File>();
        var right = new TreeMap<String,File>();
        for (var f : job.files()) { if (f.group().equals(r.group())) { left.put(f.projectName()+"/"+f.path(),f); } }
        for (var f : target.files) { if (f.group().equals(r.group())) { right.put(f.projectName()+"/"+f.path(),f); } }
        var keys = new java.util.TreeSet<>(left.keySet());
        keys.addAll(right.keySet());
        List<Pair> rows = new ArrayList<>();
        for (String key : keys)
        {
            var a = left.get(key); var b = right.get(key);
            if (a == null || b == null || !a.digest().equals(b.digest()))
            {
                var path = b==null ? a : b;
                rows.add(new Pair(path.projectName(),path.path(),a==null ? null : a.digest(),b==null ? null : b.digest()));
            }
        }
        return page(rows,start,binding).put("targetSha",r.targetSha()).put("documentRevisions",r.documentRevisions())
            .put("group",r.group()).put("baselineId",job.id());
    }

    private static ToolResult content(byte[] bytes,String digest,int start,String binding) throws IOException
    {
        if (start > bytes.length) { throw new IOException("Invalid evidence cursor."); }
        int end = Math.min(bytes.length,start+ProjectVersionReader.PAGE_BYTES);
        var result = ToolResult.success().put("digest",digest).put("bytes",bytes.length).put("byteOffset",start)
            .put("base64",Base64.getEncoder().encodeToString(Arrays.copyOfRange(bytes,start,end))).put("complete",end==bytes.length);
        if (end<bytes.length) { result.put("nextCursor",CURSORS.encode(end,binding)); }
        return result;
    }

    private static ToolResult page(List<?> rows,int start,String binding) throws IOException
    {
        if (start>rows.size()) { throw new IOException("Evidence list changed. Restart pagination."); }
        int end = Math.min(start+20,rows.size());
        var result = ToolResult.success().put("entries",rows.subList(start,end)).put("complete",end==rows.size());
        if (end<rows.size()) { result.put("nextCursor",CURSORS.encode(end,binding)); }
        return result;
    }
}
