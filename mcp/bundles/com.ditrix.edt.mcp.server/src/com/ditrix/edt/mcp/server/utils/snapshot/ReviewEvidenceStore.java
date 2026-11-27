/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils.snapshot;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import com.ditrix.edt.mcp.server.protocol.GsonProvider;
import com.ditrix.edt.mcp.server.utils.snapshot.ProjectSnapshotService.BuildIdentity;
import com.ditrix.edt.mcp.server.utils.snapshot.SnapshotSourcePolicy.Grant;

/** Private, content-addressed evidence. The job header becomes visible only after all reserved inputs are durable. */
final class ReviewEvidenceStore
{
    static final Duration RETENTION = Duration.ofDays(7);
    static final int MAX_JOBS = 64;
    static final long REPORT_RESERVE = 8L * 1024 * 1024;
    static final long QUOTA = 512L * 1024 * 1024;
    static final UUID INSTANCE = UUID.randomUUID();

    record Cycle(String repoId, String projectName, String branch, String changeId, String cycle,
        List<String> testProjects, Map<String,String> revisions) { }
    record File(String group,String projectName,String path,String digest,int bytes) { }
    record Job(UUID id, UUID instance, String owner, Cycle cycle, long retainUntil, BuildIdentity source,
        List<Grant> grants, Map<String,String> environment, Map<String,List<String>> selectors, List<File> files,
        String outcome, String phase, boolean baseline, String reportDigest)
    {
        String status() { return "RESERVED".equals(outcome) && !INSTANCE.equals(instance) ? "UNKNOWN" : outcome; }
        Job terminal(String state,String detail,boolean first,String report)
        {
            return new Job(id,instance,owner,cycle,retainUntil,source,grants,environment,selectors,files,state,detail,first,report);
        }
    }

    final Path root;
    private final Clock clock;
    private final long quota;

    ReviewEvidenceStore(Path root,Clock clock,long quota)
    {
        this.root = root;
        this.clock = clock;
        this.quota = quota;
    }

    private Path jobPath(UUID id) { return root.resolve("jobs").resolve(id + ".json"); }

    private Path blob(String digest) throws IOException
    {
        if (digest == null || !digest.matches("[0-9a-f]{64}")) { throw new IOException("Invalid evidence content ID."); }
        return root.resolve("blobs").resolve(digest);
    }

    private interface Work<T> { T run() throws IOException; }

    private <T> T locked(Work<T> work) throws IOException
    {
        synchronized (ReviewEvidenceStore.class)
        {
            Files.createDirectories(root.resolve("expired-cycles"));
            Files.createDirectories(root.resolve("jobs"));
            Files.createDirectories(root.resolve("blobs"));
            try (var channel = FileChannel.open(root.resolve("evidence.lock"),StandardOpenOption.WRITE,StandardOpenOption.CREATE);
                var lock = channel.lock())
            {
                return work.run();
            }
        }
    }

    static boolean sameCycle(Cycle a,Cycle b)
    {
        return a.repoId().equals(b.repoId()) && a.changeId().equals(b.changeId()) && a.cycle().equals(b.cycle())
            && a.projectName().equals(b.projectName()) && a.branch().equals(b.branch());
    }

    private Path expiryMarker(Cycle cycle)
    {
        var coordinates = List.of(cycle.repoId(),cycle.projectName(),cycle.branch(),cycle.changeId(),cycle.cycle());
        String digest = ProjectSourceManifest.contentIdOf(GsonProvider.toJson(coordinates).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return root.resolve("expired-cycles").resolve(digest);
    }

    private List<Job> jobs() throws IOException
    {
        List<Job> jobs = new ArrayList<>();
        try (var files = Files.newDirectoryStream(root.resolve("jobs"),"*.json"))
        {
            for (var file : files)
            {
                if (Files.size(file) > 12L * 1024 * 1024) { throw new IOException("Evidence metadata exceeds limit."); }
                var job = GsonProvider.fromJson(Files.readString(file),Job.class);
                if (job.retainUntil() <= clock.millis())
                {
                    Path marker = expiryMarker(job.cycle());
                    if (job.baseline() || Files.notExists(marker)) { atomic(marker, new byte[]{(byte)(job.baseline() ? 1 : 0)}); }
                    Files.delete(file);
                }
                else { jobs.add(job); }
            }
        }
        return jobs;
    }

    private long usage(List<Job> jobs) throws IOException
    {
        Set<String> live = new HashSet<>();
        long used = REPORT_RESERVE * jobs.stream().filter(j -> "RESERVED".equals(j.status())).count();
        for (var job : jobs)
        {
            for (var f : job.files()) { live.add(f.digest()); }
            if (job.reportDigest() != null) { live.add(job.reportDigest()); }
        }
        try (var blobs = Files.newDirectoryStream(root.resolve("blobs")))
        {
            for (var path : blobs)
            {
                if (live.contains(path.getFileName().toString())) { used += Files.size(path); }
                else { Files.delete(path); }
            }
        }
        return used;
    }

    Job reserve(UUID id,String owner,Cycle cycle,BuildIdentity source,List<Grant> grants,Map<String,String> env,
        Map<String,List<String>> selectors,List<File> files,Map<String,byte[]> payload) throws IOException
    {
        return locked(() -> {
            var current = jobs();
            if (current.stream().anyMatch(j -> sameCycle(j.cycle(),cycle) && "RESERVED".equals(j.status())))
            {
                throw new IOException("Cycle already has an unresolved reservation.");
            }
            long required = REPORT_RESERVE;
            long used = usage(current);
            for (var entry : payload.entrySet())
            {
                if (!ProjectSourceManifest.contentIdOf(entry.getValue()).equals(entry.getKey())) { throw new IOException("Evidence digest mismatch."); }
                if (Files.notExists(blob(entry.getKey()))) { required += entry.getValue().length; }
            }
            if (current.size() >= MAX_JOBS || required > quota - used || required > Files.getFileStore(root).getUsableSpace())
            {
                throw new IOException("Evidence quota insufficient to retain inputs and result.");
            }
            for (var entry : payload.entrySet()) { putBlob(entry.getKey(),entry.getValue()); }
            Job reservation = new Job(id,INSTANCE,owner,cycle,clock.instant().plus(RETENTION).toEpochMilli(),
                source,grants,env,selectors,files,"RESERVED","admission",false,null);
            save(reservation);
            return reservation;
        });
    }

    void discard(UUID id) throws IOException
    {
        locked(() -> { Files.deleteIfExists(jobPath(id)); return null; });
    }

    Job finish(UUID id,String outcome,String phase,String report) throws IOException
    {
        return locked(() -> {
            var current = jobs();
            Job reserved = current.stream().filter(j -> j.id().equals(id)).findFirst().orElseThrow(ReviewEvidenceStore::expired);
            if (!"RESERVED".equals(reserved.outcome()) || !reserved.instance().equals(INSTANCE)) { return reserved; }
            if (!Set.of("FAILED","BROKEN","PASSED","CANCELLED").contains(outcome)
                || (("FAILED".equals(outcome) || "PASSED".equals(outcome)) != (report != null)))
            { throw new IOException("Inconsistent terminal evidence."); }
            String digest = null;
            if (report != null)
            {
                byte[] bytes = report.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                if (bytes.length > REPORT_RESERVE) { throw new IOException("Evidence report exceeds reservation."); }
                digest = ProjectSourceManifest.contentIdOf(bytes);
                putBlob(digest,bytes);
            }
            Path marker = expiryMarker(reserved.cycle());
            boolean baselineExpired = Files.exists(marker) && !java.util.Arrays.equals(Files.readAllBytes(marker), new byte[]{0});
            boolean first = !baselineExpired && ("FAILED".equals(outcome) || "BROKEN".equals(outcome))
                && current.stream().noneMatch(j -> sameCycle(j.cycle(),reserved.cycle()) && j.baseline());
            var terminal = reserved.terminal(outcome,phase,first,digest);
            save(terminal);
            return terminal;
        });
    }

    record Listing(List<Job> jobs, boolean expiredHistory) { }

    Listing list(Cycle cycle) throws IOException
    {
        return locked(() -> {
            var live = jobs().stream().filter(j -> sameCycle(j.cycle(),cycle)).sorted(java.util.Comparator.comparingLong(Job::retainUntil)).toList();
            boolean expired = Files.exists(expiryMarker(cycle));
            if (live.isEmpty() && expired) { throw expired(); }
            return new Listing(live, expired);
        });
    }

    Job find(UUID id) throws IOException
    {
        return locked(() -> jobs().stream().filter(j -> j.id().equals(id)).findFirst().orElseThrow(ReviewEvidenceStore::expired));
    }

    byte[] read(UUID id,String digest) throws IOException
    {
        return locked(() -> {
            var job = jobs().stream().filter(j -> j.id().equals(id)).findFirst().orElseThrow(ReviewEvidenceStore::expired);
            if (!digest.equals(job.reportDigest()) && job.files().stream().noneMatch(f -> f.digest().equals(digest))) { throw expired(); }
            return readBlob(digest);
        });
    }

    List<ProjectSourceManifest.Entry> sourceEntries(UUID id, String projectName) throws IOException
    {
        return locked(() -> {
            var job = jobs().stream().filter(j -> j.id().equals(id)).findFirst().orElseThrow(ReviewEvidenceStore::expired);
            var entries = new ArrayList<ProjectSourceManifest.Entry>();
            for (var file : job.files())
            {
                if (file.projectName().equals(projectName) && !"documents".equals(file.group()))
                {
                    var version = new ProjectSourceManifest.Version(readBlob(file.digest()));
                    entries.add(new ProjectSourceManifest.Entry(file.path(), null, null, version));
                }
            }
            return entries;
        });
    }

    private byte[] readBlob(String digest) throws IOException
    {
        byte[] bytes = Files.readAllBytes(blob(digest));
        if (!digest.equals(ProjectSourceManifest.contentIdOf(bytes))) { throw new IOException("Evidence bytes are incomplete."); }
        return bytes;
    }

    private void putBlob(String digest,byte[] bytes) throws IOException
    {
        Path target = blob(digest);
        if (Files.notExists(target)) { atomic(target,bytes); }
    }

    private void save(Job job) throws IOException
    {
        atomic(jobPath(job.id()),GsonProvider.toJson(job).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static void atomic(Path target,byte[] bytes) throws IOException
    {
        Path temp = Files.createTempFile(target.getParent(),"pending-",".tmp");
        try
        {
            try (var stream = FileChannel.open(temp,StandardOpenOption.WRITE))
            {
                ByteBuffer data = ByteBuffer.wrap(bytes);
                while (data.hasRemaining()) { stream.write(data); }
                stream.force(true);
            }
            Files.move(temp,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        }
        finally { Files.deleteIfExists(temp); }
    }

    private static IOException expired() { return new IOException("Evidence is unavailable or its retention period has expired."); }
}
