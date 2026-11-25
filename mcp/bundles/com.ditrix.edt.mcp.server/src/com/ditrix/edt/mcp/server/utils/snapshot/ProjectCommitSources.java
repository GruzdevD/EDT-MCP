/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils.snapshot;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectInserter;

import com.ditrix.edt.mcp.server.utils.snapshot.ProjectCommitPlan.Change;
import com.ditrix.edt.mcp.server.utils.snapshot.ProjectCommitPlan.SelectedEntry;
import com.ditrix.edt.mcp.server.utils.snapshot.WindowsSourceTree.Directory;
import com.ditrix.edt.mcp.server.utils.snapshot.WindowsSourceTree.Handle;

/** Pins present files and checks deleted paths by their exact names, including case-only moves. */
final class ProjectCommitSources implements ProjectCommitExecution.SourceGuard
{
    private final WindowsSourceTree tree;
    private final List<Handle> pinned = new ArrayList<>();
    private final List<SelectedEntry> deleted = new ArrayList<>();

    ProjectCommitSources(WindowsSourceTree tree, List<Change> changes) throws IOException
    {
        this.tree = tree;
        boolean ready = false;
        try (var formatter = new ObjectInserter.Formatter())
        {
            for (Change change : changes)
            {
                if (change.blob() == null)
                {
                    deleted.add(change.selected());
                    continue;
                }
                SelectedEntry entry = change.selected();
                String[] parts = entry.source().path().split("/");
                Directory parent = parent(entry, parts, pinned);
                String name = parts[parts.length - 1];
                if (parent == null || !hasName(parent, name))
                {
                    throw ProjectCommitPlan.stale();
                }
                var file = parent.pinFileIfPresent(name);
                if (file == null)
                {
                    throw ProjectCommitPlan.stale();
                }
                pinned.add(file);
                byte[] bytes = file.read(ProjectSourceManifest.MAX_FILE_BYTES);
                if (!change.blob().equals(formatter.idFor(Constants.OBJ_BLOB, bytes)) || !hasName(parent, name))
                {
                    throw ProjectCommitPlan.stale();
                }
            }
            ready = true;
        }
        finally
        {
            if (!ready)
            {
                close();
            }
        }
    }

    @Override
    public void checkDeletedPaths() throws IOException
    {
        for (SelectedEntry entry : deleted)
        {
            List<Handle> temporary = new ArrayList<>();
            try
            {
                String[] parts = entry.source().path().split("/");
                Directory parent = parent(entry, parts, temporary);
                if (parent != null && hasName(parent, parts[parts.length - 1]))
                {
                    throw ProjectCommitPlan.stale();
                }
            }
            finally
            {
                release(temporary);
            }
        }
    }

    private Directory parent(SelectedEntry entry, String[] parts, List<Handle> handles) throws IOException
    {
        Directory directory = tree.openProject(entry.grant().location());
        handles.add(directory);
        for (int position = 0; position < parts.length - 1; position++)
        {
            if (!hasName(directory, parts[position]))
            {
                return null;
            }
            directory = directory.child(parts[position]);
            handles.add(directory);
        }
        return directory;
    }

    private static boolean hasName(Directory directory, String name) throws IOException
    {
        return directory.entries(ProjectSourceManifest.MAX_DIRECTORY_ENTRIES).stream()
            .anyMatch(entry -> entry.name().equals(name));
    }

    @Override
    public void close()
    {
        release(pinned);
    }

    private static void release(List<Handle> handles)
    {
        for (int i = handles.size() - 1; i >= 0; i--)
        {
            handles.get(i).close();
        }
        handles.clear();
    }
}
