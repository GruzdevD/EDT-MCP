/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils.snapshot;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import com.ditrix.edt.mcp.server.protocol.GsonProvider;
import com.ditrix.edt.mcp.server.utils.snapshot.ProjectCommitExecution.Prepared;
import com.ditrix.edt.mcp.server.utils.snapshot.SnapshotSourcePolicy.Grant;

/** Process-persistent receipts in the trusted plugin state directory. */
final class ProjectCommitJournal implements AutoCloseable
{
    private static final int MAX_BYTES = 2 * 1024 * 1024;
    private static final int MAX_ENTRIES = 64;
    private static final Duration RESULT_TTL = Duration.ofMinutes(10);

    enum Phase { PREPARED, PUBLISHED, COMMITTED }

    record Scope(String workTree, List<Grant> grants, List<Grant> selected, BuildReview build)
    {
        Scope(String workTree, List<Grant> grants, List<Grant> selected)
        {
            this(workTree, grants, selected, null);
        }

        Duration retention() { return build == null ? RESULT_TTL : Duration.ofDays(7); }
    }

    record Receipt(String owner, Scope scope, Prepared prepared, Phase phase, long expiresAt)
    {
        Receipt transition(Phase next, Clock clock)
        {
            return new Receipt(owner, scope, prepared, next, clock.instant().plus(scope.retention()).toEpochMilli());
        }
    }

    private final Path root;
    private final FileChannel channel;
    private final FileLock lock;
    private final Clock clock;

    private ProjectCommitJournal(Path root, FileChannel channel, FileLock lock, Clock clock)
    {
        this.root = root;
        this.channel = channel;
        this.lock = lock;
        this.clock = clock;
    }

    static ProjectCommitJournal open(Path root, Clock clock) throws IOException
    {
        Files.createDirectories(root);
        FileChannel channel = FileChannel.open(root.resolve("journal.lock"), StandardOpenOption.WRITE,
            StandardOpenOption.CREATE);
        boolean acquired = false;
        try
        {
            FileLock lock = channel.tryLock();
            if (lock == null)
            {
                throw busy();
            }
            acquired = true;
            return new ProjectCommitJournal(root, channel, lock, clock);
        }
        catch (OverlappingFileLockException e)
        {
            throw busy();
        }
        finally
        {
            if (!acquired)
            {
                channel.close();
            }
        }
    }

    private static IOException busy()
    {
        return new IOException("A project commit is in progress. Retry the same plan shortly.");
    }

    byte[] ownershipSecret() throws IOException
    {
        Path key = root.resolve("owner.key");
        if (Files.notExists(key))
        {
            byte[] secret = new byte[32];
            new SecureRandom().nextBytes(secret);
            replace(key, secret);
        }
        if (Files.size(key) != 32)
        {
            throw new IOException("The project commit journal is unavailable.");
        }
        return Files.readAllBytes(key);
    }

    Receipt find(UUID id, String owner) throws IOException
    {
        Receipt receipt = read(path(id));
        if (receipt == null)
        {
            return null;
        }
        if (!MessageDigest.isEqual(owner.getBytes(StandardCharsets.US_ASCII),
            receipt.owner().getBytes(StandardCharsets.US_ASCII)) || expired(receipt))
        {
            throw new IOException("The commit plan is unavailable. Request a new preview.");
        }
        return receipt;
    }

    java.util.List<Object> discover(ReviewEvidence.Request query,
        com.ditrix.edt.mcp.server.transport.HttpTransport.AuthenticatedClient client) throws IOException
    {
        var rows = new java.util.ArrayList<Object>();
        byte[] secret = ownershipSecret();
        try (var files = Files.newDirectoryStream(root,"*.json"))
        {
            for (Path file : files)
            {
                var receipt = read(file);
                if (receipt == null || expired(receipt) || receipt.scope().build() == null) { continue; }
                var build = receipt.scope().build();
                var prepared = receipt.prepared();
                if (!build.repoId().equals(query.repoId()) || !build.projectName().equals(query.projectName())
                    || !build.changeId().equals(query.changeId()) || !prepared.branch().equals(query.branch())) { continue; }
                String filename = file.getFileName().toString();
                UUID id = UUID.fromString(filename.substring(0,filename.length()-5));
                if (receipt.owner().equals(client.journalOwnerTag(secret,id)))
                {
                    var scope = receipt.scope();
                    var current = ProjectSnapshotService.requireCurrentAccess(scope.grants(),client,false);
                    var grant = current.projects().stream().filter(p -> build.projectName().equals(p.grant().name()))
                        .findFirst().orElseThrow(SnapshotSourcePolicy::denied).grant().review();
                    if (grant == null) { throw SnapshotSourcePolicy.denied(); }
                    grant.require(query.repoId(), Path.of(scope.workTree()), prepared.branch());
                    ChangeArtifacts.require(grant,query.changeId(),"evidence");
                    ChangeArtifacts.require(grant,query.changeId(),"read");
                    rows.add(java.util.Map.of("planId",id,"parent",prepared.parent(),"treeId",prepared.tree(),
                        "commitSha",prepared.commit(),"phase",receipt.phase(),"retainUntil",java.time.Instant.ofEpochMilli(receipt.expiresAt()).toString()));
                }
            }
        }
        return rows;
    }

    void finish(UUID id, Receipt receipt) throws IOException
    {
        save(id, receipt.transition(Phase.COMMITTED, clock));
    }

    ProjectCommitExecution.Journal begin(UUID id, String owner, Scope scope) throws IOException
    {
        prune(scope.workTree());
        if (!Files.notExists(path(id)))
        {
            throw new IOException("The commit plan already has a journal entry. Retry its confirmation.");
        }
        return new ProjectCommitExecution.Journal()
        {
            private Receipt receipt;

            @Override
            public void prepare(Prepared prepared) throws IOException
            {
                receipt = new Receipt(owner, scope, prepared, Phase.PREPARED,
                    clock.instant().plus(scope.retention()).toEpochMilli());
                save(id, receipt);
            }

            @Override
            public void discard() throws IOException
            {
                Files.delete(path(id));
            }

            @Override
            public void published() throws IOException
            {
                receipt = receipt.transition(Phase.PUBLISHED, clock);
                save(id, receipt);
            }

            @Override
            public void finish() throws IOException
            {
                ProjectCommitJournal.this.finish(id, receipt);
            }
        };
    }

    private void prune(String workTree) throws IOException
    {
        int count = 0;
        try (var files = Files.newDirectoryStream(root, "*.json"))
        {
            for (Path file : files)
            {
                Receipt receipt = read(file);
                if (receipt == null)
                {
                    continue;
                }
                if (expired(receipt))
                {
                    Files.delete(file);
                    continue;
                }
                if (receipt.phase() != Phase.COMMITTED && workTree.equalsIgnoreCase(receipt.scope().workTree()))
                {
                    throw new IOException("A previous project commit in this repository needs recovery. Retry its plan ID.");
                }
                if (++count >= MAX_ENTRIES)
                {
                    throw new IOException("The project commit journal is full. Retry after completed receipts expire.");
                }
            }
        }
    }

    private boolean expired(Receipt receipt)
    {
        return receipt.phase() == Phase.COMMITTED && clock.millis() >= receipt.expiresAt();
    }

    private static Receipt read(Path path) throws IOException
    {
        if (Files.notExists(path))
        {
            return null;
        }
        if (Files.size(path) > MAX_BYTES)
        {
            throw unreadable();
        }
        try
        {
            Receipt receipt = GsonProvider.fromJson(Files.readString(path), Receipt.class);
            if (receipt == null || receipt.owner() == null || receipt.scope() == null || receipt.prepared() == null
                || receipt.phase() == null)
            {
                throw unreadable();
            }
            return receipt;
        }
        catch (RuntimeException e)
        {
            throw unreadable();
        }
    }

    private static IOException unreadable()
    {
        return new IOException("The project commit journal is unreadable. Ask the EDT administrator to verify it.");
    }

    private void save(UUID id, Receipt receipt) throws IOException
    {
        byte[] data = GsonProvider.toJson(receipt).getBytes(StandardCharsets.UTF_8);
        if (data.length > MAX_BYTES)
        {
            throw new IOException("The project commit journal entry exceeds its limit.");
        }
        replace(path(id), data);
    }

    private void replace(Path destination, byte[] data) throws IOException
    {
        Path temp = Files.createTempFile(root, "commit-", ".tmp");
        try
        {
            try (FileChannel out = FileChannel.open(temp, StandardOpenOption.WRITE))
            {
                ByteBuffer bytes = ByteBuffer.wrap(data);
                while (bytes.hasRemaining())
                {
                    out.write(bytes);
                }
                out.force(true);
            }
            Files.move(temp, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        }
        finally
        {
            Files.deleteIfExists(temp);
        }
    }

    private Path path(UUID id)
    {
        return root.resolve(id + ".json");
    }

    @Override
    public void close() throws IOException
    {
        try (channel; lock)
        {
            // Close the lock before the channel.
        }
    }
}
