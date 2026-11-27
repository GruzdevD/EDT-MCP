/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils.snapshot;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Set;
import java.util.Map;
import java.util.HashMap;
import java.util.HashSet;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/** Optional server policy for versioned review, independent of ordinary snapshot access. */
record ReviewScope(String repoId, String workTree, Set<String> branches, Set<String> stageBranches,
    Map<String, Set<String>> changes, Set<String> perimeterPaths)
{
    ReviewScope(String repoId,String workTree,Set<String> branches,Set<String> stageBranches,Map<String,Set<String>> changes)
    {
        this(repoId,workTree,branches,stageBranches,changes,Set.of());
    }
    ReviewScope { perimeterPaths = perimeterPaths == null ? Set.of() : Set.copyOf(perimeterPaths); }
    ReviewScope(String repoId, String workTree, Set<String> branches)
    {
        this(repoId, workTree, branches, Set.of(), Map.of());
    }
    static ReviewScope configured(JsonObject project) throws IOException
    {
        if (!project.has("review"))
        {
            return null;
        }
        JsonObject review = project.getAsJsonObject("review");
        String id = string(review, "repoId");
        String location = string(review, "workTree");
        if (!id.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,63}") || !WindowsSourceTree.isDrivePath(location)
            || location.length() <= 3 || location.length() > 4096)
        {
            throw SnapshotSourcePolicy.denied();
        }
        for (String part : location.substring(3).split("\\\\", -1))
        {
            if (!WindowsSourceTree.isComponent(part))
            {
                throw SnapshotSourcePolicy.denied();
            }
        }
        var branches = SnapshotSourcePolicy.commitBranches(review.get("branches"));
        if (branches.isEmpty())
        {
            throw SnapshotSourcePolicy.denied();
        }
        Set<String> stage = SnapshotSourcePolicy.commitBranches(review.get("stageBranches"));
        if (!branches.containsAll(stage))
        {
            throw SnapshotSourcePolicy.denied();
        }
        var changes = new HashMap<String, Set<String>>();
        if (review.has("changes"))
        {
            for (var entry : review.getAsJsonObject("changes").entrySet())
            {
                if (changes.size() >= 128 || !ChangeArtifacts.validId(entry.getKey()))
                {
                    throw SnapshotSourcePolicy.denied();
                }
                var allowed = new HashSet<String>();
                for (JsonElement item : entry.getValue().getAsJsonArray())
                {
                    if (!item.isJsonPrimitive() || !item.getAsJsonPrimitive().isString()
                        || !Set.of("read", "write", "report", "append", "include", "evidence").contains(item.getAsString())
                        || !allowed.add(item.getAsString()))
                    {
                        throw SnapshotSourcePolicy.denied();
                    }
                }
                changes.put(entry.getKey(), Set.copyOf(allowed));
            }
        }
        var paths = new java.util.TreeSet<String>();
        if (review.has("perimeterPaths"))
        {
            for (var item : review.getAsJsonArray("perimeterPaths"))
            {
                String path = item.getAsString();
                if (paths.size() >= 32 || path.length() > 256 || !paths.add(path)) { throw SnapshotSourcePolicy.denied(); }
                for (String part : path.split("/",-1))
                {
                    if (!WindowsSourceTree.isComponent(part) || part.equalsIgnoreCase(".git")) { throw SnapshotSourcePolicy.denied(); }
                }
            }
        }
        return new ReviewScope(id, location, branches, stage, Map.copyOf(changes),paths);
    }

    private static String string(JsonObject object, String key) throws IOException
    {
        JsonElement value = object.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString())
        {
            throw SnapshotSourcePolicy.denied();
        }
        return value.getAsString();
    }

    void require(String requestedRepo, Path actualRoot, String ref) throws IOException
    {
        if (!repoId.equals(requestedRepo) || !Path.of(workTree).equals(actualRoot) || !branches.contains(ref))
        {
            throw SnapshotSourcePolicy.denied();
        }
    }
}
