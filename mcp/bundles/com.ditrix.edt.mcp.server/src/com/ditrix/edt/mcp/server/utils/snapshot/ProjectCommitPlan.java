/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils.snapshot;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.dircache.DirCacheEditor;
import org.eclipse.jgit.dircache.DirCacheEntry;
import org.eclipse.jgit.errors.DirCacheNameConflictException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevWalk;

import com.ditrix.edt.mcp.server.utils.snapshot.ProjectSnapshotCapture.ModelStatus;
import com.ditrix.edt.mcp.server.utils.snapshot.ProjectSnapshotRegistry.Retained;
import com.ditrix.edt.mcp.server.utils.snapshot.ProjectSourceManifest.Entry;
import com.ditrix.edt.mcp.server.utils.snapshot.ProjectSourceManifest.Project;
import com.ditrix.edt.mcp.server.utils.snapshot.ProjectSourceManifest.Version;
import com.ditrix.edt.mcp.server.utils.snapshot.SnapshotSourcePolicy.Grant;

/** Resolves retained source entries and computes the exact Git tree to be presented for review. */
final class ProjectCommitPlan
{
    static final int MAX_ENTRIES = 100;
    static final int MAX_MESSAGE_BYTES = 8192;

    record SelectedEntry(UUID id, Project project, Grant grant, Entry source, String gitPath) { }

    record Selection(Path workTree, String branch, ObjectId parent, ModelStatus modelStatus,
        List<SelectedEntry> entries, Map<UUID, String> renamePeers) { }

    record Change(SelectedEntry selected, FileMode mode, ObjectId blob) { }

    record Edit(String path, FileMode mode, ObjectId blob) { }

    record Plan(Selection selection, ObjectId tree, String message, List<Change> changes)
    {
        List<Edit> edits()
        {
            return changes.stream().map(change -> new Edit(change.selected().gitPath(), change.mode(), change.blob()))
                .toList();
        }
    }

    private ProjectCommitPlan()
    {
    }

    static Selection select(Retained retained, List<UUID> entryIds) throws IOException
    {
        if (retained.snapshot().modelStatus() == ModelStatus.DIVERGED)
        {
            throw new IOException("The EDT model diverged from the snapshot. Resolve the divergence and capture again.");
        }
        Set<UUID> requested = new HashSet<>(entryIds);
        if (requested.isEmpty() || entryIds.size() > MAX_ENTRIES || requested.size() != entryIds.size()
            || requested.contains(null))
        {
            throw new IOException("Select between 1 and " + MAX_ENTRIES + " distinct snapshot entry IDs.");
        }
        Map<String, Grant> grants = retained.scope().stream().collect(Collectors.toMap(Grant::name, Function.identity()));
        List<SelectedEntry> selected = new ArrayList<>(entryIds.size());
        Map<UUID, SelectedEntry> changed = new HashMap<>();
        for (Project project : retained.snapshot().projects())
        {
            Grant grant = grants.get(project.name());
            if (grant == null || project.workTree() == null)
            {
                continue;
            }
            Path location = Path.of(grant.location());
            if (!location.startsWith(project.workTree()))
            {
                throw SnapshotSourcePolicy.denied();
            }
            for (Entry entry : project.entries())
            {
                if (!grant.permits(entry.path()) || same(entry.head(), entry.disk()))
                {
                    continue;
                }
                UUID id = ProjectSnapshotPages.entryId(retained.id(), project.name(), entry.path());
                String gitPath = project.workTree().relativize(location.resolve(entry.path())).toString().replace('\\', '/');
                SelectedEntry choice = new SelectedEntry(id, project, grant, entry, gitPath);
                changed.put(id, choice);
                if (requested.contains(id))
                {
                    selected.add(choice);
                }
            }
        }
        if (selected.size() != requested.size())
        {
            throw new IOException("Select changed disk entries from this snapshot's authorized scope.");
        }
        Project first = selected.get(0).project();
        if (first.head() == null || !ObjectId.isId(first.head()) || first.branch() == null
            || selected.stream().anyMatch(entry -> !first.workTree().equals(entry.project().workTree())
                || !Objects.equals(first.head(), entry.project().head()) || !first.branch().equals(entry.project().branch()))
            || selected.stream().map(SelectedEntry::gitPath).distinct().count() != selected.size())
        {
            throw new IOException("Select entries from one Git work tree and one captured HEAD.");
        }
        Map<UUID, String> renames = identifyRenames(first.workTree(), changed.values(), requested);
        return new Selection(first.workTree(), first.branch(), ObjectId.fromString(first.head()),
            retained.snapshot().modelStatus(), List.copyOf(selected), renames);
    }

    private static Map<UUID, String> identifyRenames(Path workTree, Iterable<SelectedEntry> entries, Set<UUID> requested)
        throws IOException
    {
        Map<String, List<SelectedEntry>> deletions = new HashMap<>();
        Map<String, List<SelectedEntry>> additions = new HashMap<>();
        for (SelectedEntry entry : entries)
        {
            if (!workTree.equals(entry.project().workTree()))
            {
                continue;
            }
            if (entry.source().disk() == null)
            {
                deletions.computeIfAbsent(entry.source().head().contentId(), key -> new ArrayList<>()).add(entry);
            }
            else if (entry.source().head() == null)
            {
                additions.computeIfAbsent(entry.source().disk().contentId(), key -> new ArrayList<>()).add(entry);
            }
        }
        Map<UUID, String> renames = new HashMap<>();
        for (var deletion : deletions.entrySet())
        {
            List<SelectedEntry> destinations = additions.get(deletion.getKey());
            if (deletion.getValue().size() != 1 || destinations == null || destinations.size() != 1)
            {
                continue;
            }
            SelectedEntry before = deletion.getValue().get(0);
            SelectedEntry after = destinations.get(0);
            if (requested.contains(before.id()) != requested.contains(after.id()))
            {
                throw new IOException("Select both snapshot entry IDs for an identified rename.");
            }
            if (requested.contains(before.id()))
            {
                renames.put(before.id(), after.gitPath());
                renames.put(after.id(), before.gitPath());
            }
        }
        return Map.copyOf(renames);
    }

    static Plan build(Repository repository, Selection selection, String message) throws IOException
    {
        if (message == null || message.isBlank() || message.indexOf('\0') >= 0
            || !StandardCharsets.UTF_8.newEncoder().canEncode(message)
            || message.getBytes(StandardCharsets.UTF_8).length > MAX_MESSAGE_BYTES)
        {
            throw new IOException("Supply a non-blank UTF-8 commit message of at most " + MAX_MESSAGE_BYTES + " bytes.");
        }
        if (!selection.workTree().equals(repository.getWorkTree().toPath()))
        {
            throw SnapshotSourcePolicy.denied();
        }
        try (var revisions = new RevWalk(repository); var hash = new TreeHasher())
        {
            DirCache tree = readTree(revisions, selection.parent());
            List<Change> changes = new ArrayList<>(selection.entries().size());
            for (SelectedEntry entry : selection.entries())
            {
                DirCacheEntry base = tree.getEntry(entry.gitPath());
                ObjectId captured = blobId(hash, entry.source().head());
                if (!Objects.equals(captured, base == null ? null : base.getObjectId()))
                {
                    throw stale();
                }
                FileMode mode = base == null ? FileMode.REGULAR_FILE : base.getFileMode();
                if (mode != FileMode.REGULAR_FILE && mode != FileMode.EXECUTABLE_FILE)
                {
                    throw new IOException("The selected source is not a regular Git file.");
                }
                if (base == null && selection.renamePeers().containsKey(entry.id()))
                {
                    DirCacheEntry origin = tree.getEntry(selection.renamePeers().get(entry.id()));
                    if (origin != null && origin.getFileMode() == FileMode.EXECUTABLE_FILE)
                    {
                        mode = FileMode.EXECUTABLE_FILE;
                    }
                }
                changes.add(new Change(entry, mode, blobId(hash, entry.source().disk())));
            }
            Plan pending = new Plan(selection, null, message, List.copyOf(changes));
            apply(tree, pending.edits());
            return new Plan(selection, tree.writeTree(hash), message, pending.changes());
        }
    }

    static DirCache readTree(RevWalk revisions, ObjectId parent) throws IOException
    {
        return DirCache.read(revisions.getObjectReader(), revisions.parseCommit(parent).getTree());
    }

    static void apply(DirCache cache, List<Edit> edits) throws IOException
    {
        DirCacheEditor editor = cache.editor();
        for (Edit edit : edits)
        {
            if (edit.blob() == null)
            {
                editor.add(new DirCacheEditor.DeletePath(edit.path()));
            }
            else
            {
                editor.add(new DirCacheEditor.PathEdit(edit.path())
                {
                    @Override
                    public void apply(DirCacheEntry entry)
                    {
                        entry.setFileMode(edit.mode());
                        entry.setObjectId(edit.blob());
                        entry.setLength(0);
                        entry.setLastModified(Instant.EPOCH);
                    }
                }.setReplace(false));
            }
        }
        try
        {
            editor.finish();
        }
        catch (DirCacheNameConflictException e)
        {
            throw new IOException("The selected paths conflict with other Git entries.");
        }
    }

    static ObjectId blobId(ObjectInserter formatter, Version version)
    {
        return version == null ? null : formatter.idFor(Constants.OBJ_BLOB, version.bytes());
    }

    private static boolean same(Version first, Version second)
    {
        return Objects.equals(first == null ? null : first.contentId(), second == null ? null : second.contentId());
    }

    static IOException stale()
    {
        return new IOException("The selected Git state changed. Capture a new snapshot and preview the commit again.");
    }

    private static final class TreeHasher extends ObjectInserter.Formatter
    {
        @Override
        public ObjectId insert(int type, byte[] bytes, int offset, int length)
        {
            return idFor(type, bytes, offset, length);
        }

        @Override
        public ObjectId insert(int type, long length, InputStream input) throws IOException
        {
            return idFor(type, length, input);
        }
    }
}
