/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils.snapshot;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevWalk;

import com.ditrix.edt.mcp.server.utils.snapshot.ProjectCommitPlan.Edit;
import com.ditrix.edt.mcp.server.utils.snapshot.ProjectCommitPlan.Plan;

/** Commits a reviewed tree while holding the conventional Git index and HEAD locks. */
final class ProjectCommitExecution
{
    private ProjectCommitExecution()
    {
    }

    interface SourceGuard extends AutoCloseable
    {
        void checkDeletedPaths() throws IOException;

        @Override
        void close();
    }

    @FunctionalInterface
    interface PinSources
    {
        SourceGuard pin() throws IOException;
    }

    @FunctionalInterface
    interface Check
    {
        void run() throws IOException;
    }

    interface Journal
    {
        void prepare(Prepared prepared) throws IOException;

        void discard() throws IOException;

        void published() throws IOException;

        void finish() throws IOException;
    }

    record IndexValue(String blob, int mode) { }

    record IndexChange(String path, IndexValue before, IndexValue after) { }

    record Prepared(String branch, String parent, String commit, String tree, List<IndexChange> edits) { }

    enum Recovery { COMMITTED, RECOVERY_REQUIRED }

    static Prepared commit(Repository repository, Plan plan, Runnable releaseIndexPin, PinSources sources,
        Check verifyAccess, Journal journal) throws IOException
    {
        var selection = plan.selection();
        try (var locks = Locks.acquire(repository))
        {
            repository.getRefDatabase().refresh();
            ProjectCommitPolicy.requireRepository(repository, selection);
            ProjectCommitPolicy.requireIndex(locks.index, plan);
            try (SourceGuard pinned = sources.pin())
            {
                PersonIdent identity = new PersonIdent(repository);
                ObjectId commit = writeCommit(repository, plan, identity);
                List<Edit> edits = plan.edits();
                var prepared = new Prepared(selection.branch(), selection.parent().name(), commit.name(),
                    plan.tree().name(), describeIndexChanges(locks.index, edits));
                ProjectCommitPlan.apply(locks.index, edits);
                locks.index.write();

                RefUpdate update = repository.updateRef(selection.branch(), true);
                if (update.getRef().isSymbolic())
                {
                    throw ProjectCommitPlan.stale();
                }
                update.setExpectedOldObjectId(selection.parent());
                update.setNewObjectId(commit);
                update.setRefLogIdent(identity);
                update.setRefLogMessage("commit (EDT MCP)", false);
                journal.prepare(prepared);
                try
                {
                    verifyAccess.run();
                    pinned.checkDeletedPaths();
                }
                catch (IOException e)
                {
                    discard(journal, e);
                    throw e;
                }
                var result = update.update();
                if (result != RefUpdate.Result.FAST_FORWARD)
                {
                    if (result == RefUpdate.Result.LOCK_FAILURE || result == RefUpdate.Result.REJECTED)
                    {
                        var stale = ProjectCommitPlan.stale();
                        discard(journal, stale);
                        throw stale;
                    }
                    throw new IOException("Could not verify publication of the project commit.");
                }
                journal.published();
                if (!hasPublishedRef(repository, prepared))
                {
                    throw new IOException("The branch changed before the project commit index was updated.");
                }
                releaseIndexPin.run();
                if (!locks.index.commit())
                {
                    throw new IOException("Git published the project commit but could not replace the index.");
                }
                journal.finish();
                return prepared;
            }
        }
    }

    static Recovery recover(Repository repository, Prepared prepared, Runnable releaseIndexPin) throws IOException
    {
        try (var locks = Locks.acquire(repository))
        {
            if (!hasPublishedRef(repository, prepared) || locks.index.hasUnmergedPaths())
            {
                return Recovery.RECOVERY_REQUIRED;
            }
            try (var revisions = new RevWalk(repository))
            {
                var commit = revisions.parseCommit(ObjectId.fromString(prepared.commit()));
                if (!commit.getTree().name().equals(prepared.tree()) || commit.getParentCount() != 1
                    || !commit.getParent(0).name().equals(prepared.parent()))
                {
                    return Recovery.RECOVERY_REQUIRED;
                }
            }
            boolean matchesBefore = true;
            boolean matchesAfter = true;
            for (IndexChange change : prepared.edits())
            {
                var entry = locks.index.getEntry(change.path());
                if (entry != null && (entry.isAssumeValid() || entry.isSkipWorkTree() || entry.isIntentToAdd()))
                {
                    return Recovery.RECOVERY_REQUIRED;
                }
                IndexValue actual = indexValue(locks.index, change.path());
                matchesBefore &= Objects.equals(actual, change.before());
                matchesAfter &= Objects.equals(actual, change.after());
            }
            if (matchesAfter)
            {
                return Recovery.COMMITTED;
            }
            if (!matchesBefore)
            {
                return Recovery.RECOVERY_REQUIRED;
            }
            List<Edit> edits = prepared.edits().stream().map(change -> change.after() == null
                ? new Edit(change.path(), FileMode.MISSING, null)
                : new Edit(change.path(), FileMode.fromBits(change.after().mode()), ObjectId.fromString(change.after().blob())))
                .toList();
            ProjectCommitPlan.apply(locks.index, edits);
            locks.index.write();
            if (!hasPublishedRef(repository, prepared))
            {
                return Recovery.RECOVERY_REQUIRED;
            }
            releaseIndexPin.run();
            return locks.index.commit() ? Recovery.COMMITTED : Recovery.RECOVERY_REQUIRED;
        }
    }

    private static boolean hasPublishedRef(Repository repository, Prepared prepared) throws IOException
    {
        repository.getRefDatabase().refresh();
        var head = repository.exactRef(Constants.HEAD);
        return head != null && head.isSymbolic() && !head.getTarget().isSymbolic()
            && prepared.branch().equals(head.getTarget().getName()) && head.getObjectId() != null
            && prepared.commit().equals(head.getObjectId().name());
    }

    private static void discard(Journal journal, IOException original)
    {
        try
        {
            journal.discard();
        }
        catch (IOException e)
        {
            original.addSuppressed(e);
        }
    }

    private static ObjectId writeCommit(Repository repository, Plan plan, PersonIdent identity) throws IOException
    {
        try (var revisions = new RevWalk(repository); var inserter = repository.newObjectInserter())
        {
            var tree = ProjectCommitPlan.readTree(revisions, plan.selection().parent());
            for (var change : plan.changes())
            {
                if (change.blob() != null)
                {
                    ObjectId blob = inserter.insert(Constants.OBJ_BLOB, change.selected().source().disk().bytes());
                    if (!blob.equals(change.blob()))
                    {
                        throw ProjectCommitPlan.stale();
                    }
                }
            }
            ProjectCommitPlan.apply(tree, plan.edits());
            if (!plan.tree().equals(tree.writeTree(inserter)))
            {
                throw ProjectCommitPlan.stale();
            }
            var commit = new CommitBuilder();
            commit.setAuthor(identity);
            commit.setCommitter(identity);
            commit.setParentId(plan.selection().parent());
            commit.setTreeId(plan.tree());
            commit.setMessage(plan.message());
            ObjectId sha = inserter.insert(commit);
            inserter.flush();
            return sha;
        }
    }

    private static List<IndexChange> describeIndexChanges(DirCache index, List<Edit> edits)
    {
        List<IndexChange> values = new ArrayList<>(edits.size());
        for (Edit edit : edits)
        {
            IndexValue before = indexValue(index, edit.path());
            IndexValue after = edit.blob() == null ? null : new IndexValue(edit.blob().name(), edit.mode().getBits());
            if (!Objects.equals(before, after))
            {
                values.add(new IndexChange(edit.path(), before, after));
            }
        }
        return List.copyOf(values);
    }

    private static IndexValue indexValue(DirCache index, String path)
    {
        var entry = index.getEntry(path);
        return entry == null ? null : new IndexValue(entry.getObjectId().name(), entry.getRawMode());
    }

    private static final class Locks implements AutoCloseable
    {
        private final DirCache index;
        private final Path headLock;

        private Locks(DirCache index, Path headLock)
        {
            this.index = index;
            this.headLock = headLock;
        }

        static Locks acquire(Repository repository) throws IOException
        {
            DirCache index = repository.lockDirCache();
            Path headLock = repository.getDirectory().toPath().resolve("HEAD.lock");
            boolean ready = false;
            try
            {
                Files.createFile(headLock);
                ready = true;
                return new Locks(index, headLock);
            }
            catch (FileAlreadyExistsException e)
            {
                throw new IOException("Git HEAD is locked. Retry after the other Git operation finishes.");
            }
            finally
            {
                if (!ready)
                {
                    index.unlock();
                }
            }
        }

        @Override
        public void close() throws IOException
        {
            try
            {
                index.unlock();
            }
            finally
            {
                Files.deleteIfExists(headLock);
            }
        }
    }
}
