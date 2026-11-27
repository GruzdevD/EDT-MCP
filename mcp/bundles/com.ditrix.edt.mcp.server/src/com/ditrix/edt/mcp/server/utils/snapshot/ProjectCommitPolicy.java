/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils.snapshot;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.dircache.DirCacheEntry;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.RepositoryState;

import com.ditrix.edt.mcp.server.utils.snapshot.ProjectCommitPlan.Plan;
import com.ditrix.edt.mcp.server.utils.snapshot.ProjectCommitPlan.Selection;
import com.ditrix.edt.mcp.server.utils.snapshot.SnapshotSourcePolicy.Grant;

/** Checks current permissions and the Git conditions common to planning and confirmation. */
final class ProjectCommitPolicy
{
    private static final List<String> COMMIT_HOOKS = List.of("pre-commit", "prepare-commit-msg", "commit-msg",
        "post-commit", "reference-transaction");

    private ProjectCommitPolicy()
    {
    }

    static void requireGrants(Selection selection, List<Grant> current, boolean write) throws IOException
    {
        requireGrants(selection.entries().stream().map(ProjectCommitPlan.SelectedEntry::grant).distinct().toList(),
            selection.branch(), current, write);
    }

    static void requireGrants(List<Grant> selected, String branch, List<Grant> current, boolean write) throws IOException
    {
        Map<String, Grant> grants = current.stream().collect(Collectors.toMap(Grant::name, Function.identity()));
        for (Grant original : selected)
        {
            Grant grant = grants.get(original.name());
            if (grant == null || !grant.readScope().equals(original.readScope())
                || !grant.commitBranches().contains(branch) || write && !grant.commit())
            {
                throw new IOException("Project commit access is unavailable for this caller, scope, or branch.");
            }
        }
    }

    static void requireRepository(Repository repository, Selection selection) throws IOException
    {
        Ref head = repository.exactRef(Constants.HEAD);
        if (head == null || !head.isSymbolic() || head.getTarget().isSymbolic()
            || !head.getTarget().getName().startsWith(Constants.R_HEADS))
        {
            throw new IOException("Check out an allowed local branch before previewing or confirming the commit.");
        }
        if (!head.getTarget().getName().equals(selection.branch()) || !selection.parent().equals(head.getObjectId()))
        {
            throw ProjectCommitPlan.stale();
        }
        if (repository.getRepositoryState() != RepositoryState.SAFE)
        {
            throw new IOException("Finish the current Git operation before previewing or confirming a project commit.");
        }
        var config = repository.getConfig();
        Path git = repository.getDirectory().toPath();
        Path hooks = git.resolve("hooks");
        if (config.getBoolean("commit", "gpgSign", false) || config.getString("core", null, "hooksPath") != null
            || COMMIT_HOOKS.stream().anyMatch(name -> !absent(hooks.resolve(name))))
        {
            throw new IOException("This repository requires external Git hooks or commit signing.");
        }
        if (config.getBoolean("core", "sparseCheckout", false) || config.getBoolean("core", "splitIndex", false)
            || !absent(git.resolve("worktrees")) || !absent(git.resolve("config.worktree")))
        {
            throw new IOException("This Git work tree or index layout is unsupported for a project commit.");
        }
        if (selection.entries().stream().anyMatch(entry -> entry.project().rawDiskComparison()))
        {
            throw new IOException("Git normalization or filters prevent confirming the raw disk bytes for this snapshot.");
        }
    }

    static void requireStageNormalization(Repository repository, Plan plan, DirCache index) throws IOException
    {
        var config = repository.getConfig();
        String crlf = config.getString("core", null, "autocrlf");
        if (defaultUserAttributesPresent(repository) || (crlf != null && !"false".equalsIgnoreCase(crlf)) || config.getString("core", null, "attributesfile") != null
            || !config.getSubsections("filter").isEmpty() || !absent(repository.getDirectory().toPath().resolve("info/attributes")))
        {
            throw ProjectCommitPlan.stale();
        }
        requireSystemAttributes(repository, plan);
        var root = repository.getWorkTree().toPath();
        var checked = new java.util.HashSet<Path>();
        for (var change : plan.changes())
        {
            for (Path parent = root.resolve(change.selected().gitPath()).getParent();
                parent != null && parent.startsWith(root) && checked.add(parent); parent = parent.getParent())
            {
                Path attributes = parent.resolve(".gitattributes");
                String gitPath = root.relativize(attributes).toString().replace(java.io.File.separatorChar, '/');
                if (!absent(attributes) || index.getEntry(gitPath) != null) { throw ProjectCommitPlan.stale(); }
            }
        }
    }

    private static void requireSystemAttributes(Repository repo, Plan plan) throws IOException
    {
        String disabled = System.getenv("GIT_ATTR_NOSYSTEM");
        if (disabled != null && List.of("true","yes","on","1").contains(disabled.toLowerCase(java.util.Locale.ROOT))) { return; }
        var systemConfig = repo.getFS().getGitSystemConfig();
        if (systemConfig == null) { return; }
        Path file = systemConfig.toPath().resolveSibling("gitattributes");
        if (absent(file)) { return; }
        // Git for Windows ships diff drivers here. They never transform bytes on check-in.
        // Accept only trivially harmless lines without interpreting Git's quoted patterns or macros.
        int limit = 256 * 1024;
        byte[] bytes;
        try (var input = Files.newInputStream(file)) { bytes = input.readNBytes(limit + 1); }
        if (bytes.length > limit) { throw ProjectCommitPlan.stale(); }
        var content = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        for (String line : content.lines().toList())
        {
            String stripped = line.strip();
            if (stripped.isEmpty() || stripped.startsWith("#")) { continue; }
            String[] parts = stripped.split("\\s+",2);
            if (parts.length == 2 && parts[1].matches("diff=[A-Za-z0-9_.-]+") && !parts[0].startsWith("[attr]")) { continue; }
            // JGit matches ordinary Git path patterns. Unknown syntax and macros fail closed.
            if (parts.length == 2 && parts[0].matches("[A-Za-z0-9_./*?-]+"))
            {
                var pattern = new org.eclipse.jgit.attributes.AttributesRule(
                    parts[0].toLowerCase(java.util.Locale.ROOT), parts[1]);
                if (plan.changes().stream().noneMatch(change -> pattern.isMatch(
                    change.selected().gitPath().toLowerCase(java.util.Locale.ROOT),false))) { continue; }
            }
            throw ProjectCommitPlan.stale();
        }
    }

    static boolean defaultUserAttributesPresent(Repository repo)
    {
        String xdg = System.getenv("XDG_CONFIG_HOME");
        var home = repo.getFS().userHome();
        Path config = xdg != null && !xdg.isBlank() ? Path.of(xdg) : home == null ? null : home.toPath().resolve(".config");
        return config != null && !absent(config.resolve("git/attributes"));
    }

    private static boolean absent(Path file)
    {
        return Files.notExists(file, LinkOption.NOFOLLOW_LINKS);
    }

    static void requireIndex(DirCache index, Plan plan) throws IOException
    {
        if (index.hasUnmergedPaths())
        {
            throw new IOException("Resolve Git index conflicts before previewing or confirming a project commit.");
        }
        try (var formatter = new ObjectInserter.Formatter())
        {
            for (var change : plan.changes())
            {
                var source = change.selected().source();
                var capturedIndex = ProjectCommitPlan.blobId(formatter, source.index());
                DirCacheEntry live = index.getEntry(change.selected().gitPath());
                if (!Objects.equals(capturedIndex, live == null ? null : live.getObjectId())
                    || live != null && (live.getFileMode() != change.mode() || live.isAssumeValid()
                        || live.isSkipWorkTree() || live.isIntentToAdd()))
                {
                    throw ProjectCommitPlan.stale();
                }
                var base = ProjectCommitPlan.blobId(formatter, source.head());
                if (!Objects.equals(capturedIndex, base) && !Objects.equals(capturedIndex, change.blob()))
                {
                    throw new IOException("A selected file is partially staged. Align its index and disk versions first.");
                }
            }
        }
    }

    static Plan preview(Repository repository, Selection selection, List<Grant> current, String message) throws IOException
    {
        requireGrants(selection, current, false);
        requireRepository(repository, selection);
        Plan plan = ProjectCommitPlan.build(repository, selection, message);
        DirCache index = repository.readDirCache();
        requireIndex(index, plan);
        // Check that applying the selected edits would preserve all other index entries.
        ProjectCommitPlan.apply(index, plan.edits());
        requireRepository(repository, selection);
        return plan;
    }
}
