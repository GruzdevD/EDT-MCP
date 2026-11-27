/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils.snapshot;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NavigableSet;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.dircache.DirCacheEntry;
import org.eclipse.jgit.ignore.IgnoreNode;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.treewalk.filter.PathFilter;

import com.ditrix.edt.mcp.server.utils.git.GitRepositoryResolver;
import com.ditrix.edt.mcp.server.utils.snapshot.SnapshotSourcePolicy.Access;
import com.ditrix.edt.mcp.server.utils.snapshot.SnapshotSourcePolicy.ScopedProject;
import com.ditrix.edt.mcp.server.utils.snapshot.WindowsSourceTree.Directory;
import com.ditrix.edt.mcp.server.utils.snapshot.WindowsSourceTree.Handle;
import com.ditrix.edt.mcp.server.utils.snapshot.WindowsSourceTree.PinnedFile;

/** Captures the three source versions with independent HEAD-to-index and index-to-disk deltas. */
final class ProjectSourceManifest
{
    private static final String SOURCE = SnapshotSourcePolicy.SOURCE_DIRECTORY;
    static final int MAX_DIRECTORY_ENTRIES = 50_000;
    private static final int MAX_DEPTH = 96;
    static final int MAX_FILE_BYTES = 4 * 1024 * 1024;
    private static final int MAX_RULE_BYTES = 256 * 1024;
    private static final long MAX_CONTENT_BYTES = 64L * 1024 * 1024;

    private ProjectSourceManifest()
    {
    }

    record Version(String contentId, byte[] bytes)
    {
        Version(byte[] bytes)
        {
            this(contentIdOf(bytes), bytes);
        }

        Version
        {
            bytes = bytes.clone();
        }

        @Override
        public byte[] bytes()
        {
            return bytes.clone();
        }

        int size()
        {
            return bytes.length;
        }
    }

    record Entry(String path, Version head, Version index, Version disk)
    {
        boolean staged()
        {
            return different(head, index);
        }

        boolean unstaged()
        {
            return different(index, disk);
        }

        private static boolean different(Version a, Version b)
        {
            return !Objects.equals(a == null ? null : a.contentId(), b == null ? null : b.contentId());
        }
    }

    record Project(String name, String branch, String head, List<Entry> entries, boolean rawDiskComparison,
        SnapshotBuildInputs buildInputs, Path workTree, List<Entry> perimeter)
    {
        Project(String name,String branch,String head,List<Entry> entries,boolean rawDiskComparison,
            SnapshotBuildInputs buildInputs,Path workTree)
        {
            this(name,branch,head,entries,rawDiskComparison,buildInputs,workTree,List.of());
        }
        List<Entry> allReviewEntries()
        {
            return java.util.stream.Stream.concat(entries.stream(),perimeter.stream()).toList();
        }
        Project(String name, String branch, String head, List<Entry> entries, boolean rawDiskComparison)
        {
            this(name, branch, head, entries, rawDiskComparison, null, null);
        }

        Project(String name, String branch, String head, List<Entry> entries, boolean rawDiskComparison,
            SnapshotBuildInputs buildInputs)
        {
            this(name, branch, head, entries, rawDiskComparison, buildInputs, null);
        }

        Project
        {
            entries = List.copyOf(entries);
            perimeter = List.copyOf(perimeter);
        }
    }

    static List<Project> capture(Access access, boolean forYaxunit) throws IOException
    {
        Budget budget = new Budget();
        List<Project> result = new ArrayList<>();
        for (ScopedProject project : access.projects())
        {
            result.add(capture(project, access.tree(), budget, forYaxunit));
        }
        if (forYaxunit)
        {
            SnapshotBuildInputs.validateScope(result);
        }
        return List.copyOf(result);
    }

    static Project capture(ScopedProject project, WindowsSourceTree local) throws IOException
    {
        return capture(project, local, new Budget(), false);
    }

    @FunctionalInterface
    interface CommitRepository<T>
    {
        T operate(Repository repository, Runnable releaseIndexPin) throws IOException;
    }

    static <T> T withCommitRepository(WindowsSourceTree local, Path root, CommitRepository<T> action)
        throws IOException
    {
        try (Directory workTree = local.openProject(root.toString()); Directory git = workTree.gitWriteDirectory();
            MetadataPins pins = new MetadataPins(git, new Budget());
            Repository repository = GitRepositoryResolver.openSnapshotWorkTree(root.toFile()))
        {
            return action.operate(repository, pins::releaseIndex);
        }
    }

    @FunctionalInterface
    interface RepositoryRead<T>
    {
        T read(Repository repository) throws IOException;
    }

    static <T> T readRepository(WindowsSourceTree tree, Path root, RepositoryRead<T> action) throws IOException
    {
        try (Directory workTree = tree.openProject(root.toString()); Directory git = workTree.child(".git");
            MetadataPins pins = new MetadataPins(git, new Budget());
            Repository repository = GitRepositoryResolver.openSnapshotWorkTree(root.toFile()))
        {
            return action.read(repository);
        }
    }

    private static Project capture(ScopedProject project, WindowsSourceTree local, Budget budget, boolean forYaxunit)
        throws IOException
    {
        try (Directory projectRoot = project.open(local))
        {
            for (Path root = project.location(); root.getParent() != null; root = root.getParent())
            {
                budget.checkTime();
                try (Directory workTree = local.openProject(root.toString()); Directory git = workTree.childIfPresent(".git"))
                {
                    if (git != null)
                    {
                        try (MetadataPins pins = new MetadataPins(git, budget))
                        {
                            return captureWorkTree(project, local, projectRoot, root, git, budget, forYaxunit);
                        }
                    }
                }
            }
            throw new IOException("No local Git work tree was found for the snapshot project.");
        }
    }

    private static final class MetadataPins implements AutoCloseable
    {
        private final List<Handle> opened = new ArrayList<>();
        private PinnedFile index;

        MetadataPins(Directory git, Budget budget) throws IOException
        {
            boolean complete = false;
            try
            {
                Directory objects = keep(git.child("objects"));
                Directory info = keep(objects.childIfPresent("info"));
                Directory packs = keep(objects.childIfPresent("pack"));
                Directory refs = keep(git.childIfPresent("refs"));
                PinnedFile head = keep(git.pinFileIfPresent("HEAD"));
                PinnedFile config = keep(git.pinFileIfPresent("config"));
                index = git.pinFileIfPresent("index");
                keep(git.pinFileIfPresent("packed-refs"));
                if (head == null || config == null || git.readIfPresent("commondir", MAX_RULE_BYTES) != null
                    || info != null && info.readIfPresent("alternates", MAX_RULE_BYTES) != null)
                {
                    throw WindowsSourceTree.unavailable();
                }
                checkMetadataEntries(refs, budget, 0, true);
                checkMetadataEntries(packs, budget, 0, false);
                complete = true;
            }
            finally
            {
                if (!complete)
                {
                    close();
                }
            }
        }

        void releaseIndex()
        {
            if (index != null)
            {
                index.close();
                index = null;
            }
        }

        private <T extends Handle> T keep(T openedHandle)
        {
            if (openedHandle != null)
            {
                opened.add(openedHandle);
            }
            return openedHandle;
        }

        @Override
        public void close()
        {
            releaseIndex();
            for (int i = opened.size() - 1; i >= 0; i--)
            {
                opened.get(i).close();
            }
        }
    }

    private static void checkMetadataEntries(Directory directory, Budget budget, int depth, boolean descend)
        throws IOException
    {
        if (directory == null)
        {
            return;
        }
        if (depth > MAX_DEPTH)
        {
            throw WindowsSourceTree.unavailable();
        }
        for (WindowsSourceTree.Entry entry : directory.entries(MAX_DIRECTORY_ENTRIES))
        {
            budget.checkTime();
            if (entry.reparsePoint())
            {
                throw WindowsSourceTree.unavailable();
            }
            if (descend && entry.directory())
            {
                try (Directory child = directory.child(entry.name()))
                {
                    checkMetadataEntries(child, budget, depth + 1, true);
                }
            }
        }
    }

    private static Project captureWorkTree(ScopedProject project, WindowsSourceTree local, Directory projectRoot,
        Path root, Directory git, Budget budget, boolean forYaxunit) throws IOException
    {
        Repository repository;
        try
        {
            repository = GitRepositoryResolver.openSnapshotWorkTree(root.toFile());
        }
        catch (IOException | RuntimeException e)
        {
            throw gitUnavailable();
        }
        try (repository; ObjectReader reader = repository.newObjectReader())
        {
            Path projectPath = root.relativize(project.location());
            String prefix = projectPath.toString().replace('\\', '/');
            if (!prefix.isEmpty())
            {
                prefix += "/";
            }
            GitEntries initial = readGitEntries(repository, reader, project, prefix, budget);
            DiskWalk disk = new DiskWalk(project, initial.index().keySet(), initial.submodules(), budget, forYaxunit);
            Rules rules = globalRules(repository, local, root, git);
            boolean ignored = false;
            Path ancestor = root;
            String ancestorPrefix = "";
            for (Path component : projectPath)
            {
                if (component.toString().isEmpty())
                {
                    break;
                }
                try (Directory directory = local.openProject(ancestor.toString()))
                {
                    rules = disk.controls(directory, ancestorPrefix, rules, ignored);
                }
                ancestor = ancestor.resolve(component);
                ignored |= rules != null && rules.ignores(ancestorPrefix + component, true);
                ancestorPrefix += component + "/";
            }
            rules = disk.controls(projectRoot, prefix, rules, ignored);
            if (!project.isWorkspaceLink(SOURCE, true) && !initial.submodules().contains(SOURCE))
            {
                try (Directory sources = projectRoot.childIfPresent(SOURCE))
                {
                    if (sources != null)
                    {
                        disk.scan(sources, SOURCE + "/", prefix, rules,
                            ignored || rules != null && rules.ignores(prefix + SOURCE, true), 0);
                    }
                }
            }
            List<Entry> entries = materialize(reader, initial, disk.versions, budget);
            if (!initial.equals(readGitEntries(repository, reader, project, prefix, budget)))
            {
                throw new IOException("Git HEAD or index changed during snapshot capture. Retry the capture.");
            }
            String autoCrlf = repository.getConfig().getString("core", null, "autocrlf");
            boolean rawComparison = disk.attributes || ProjectCommitPolicy.defaultUserAttributesPresent(repository)
                || repository.getConfig().getString("core", null, "attributesfile") != null
                || autoCrlf != null && !"false".equalsIgnoreCase(autoCrlf)
                || !repository.getConfig().getSubsections("filter").isEmpty();
            try (Directory info = git.childIfPresent("info"))
            {
                rawComparison |= info != null && info.readIfPresent("attributes", MAX_RULE_BYTES) != null;
            }
            SnapshotBuildInputs inputs = forYaxunit ? SnapshotBuildInputs.read(project, projectRoot) : null;
            return new Project(project.grant().name(), initial.branch(), initial.headId(), entries, rawComparison, inputs,
                root, perimeter(repository,local,root,initial,project,budget));
        }
    }

    private static List<Entry> perimeter(Repository repo,WindowsSourceTree local,Path root,GitEntries state,
        ScopedProject project,Budget budget) throws IOException
    {
        var grant = project.grant().review();
        if (grant == null || grant.perimeterPaths().isEmpty() || !Path.of(grant.workTree()).equals(root)
            || !grant.branches().contains(state.branch())) { return List.of(); }
        var entries = new ArrayList<Entry>();
        var index = repo.readDirCache();
        try (var walk = new RevWalk(repo); var dir = local.openProject(root.toString()))
        {
            var tree = state.headId() == null ? null : walk.parseCommit(ObjectId.fromString(state.headId())).getTree();
            for (String path : grant.perimeterPaths().stream().sorted().toList())
            {
                Version head = null;
                try (var tw = tree == null ? null : TreeWalk.forPath(repo,path,tree))
                {
                    if (tw != null) { head = perimeterBlob(repo,tw.getFileMode(0),tw.getObjectId(0),budget); }
                }
                var entry = index.getEntry(path);
                if (entry != null && entry.getStage() != 0) { throw new IOException("Unresolved perimeter index."); }
                Version staged = entry == null ? null : perimeterBlob(repo,entry.getFileMode(),entry.getObjectId(),budget);
                byte[] disk = perimeterDisk(dir,path.split("/"),0,budget.fileLimit());
                entries.add(new Entry(path,head,staged,disk == null ? null : budget.keep(disk)));
            }
        }
        return entries;
    }

    private static Version perimeterBlob(Repository repo,FileMode mode,ObjectId id,Budget budget) throws IOException
    {
        if (mode != FileMode.REGULAR_FILE && mode != FileMode.EXECUTABLE_FILE) { throw new IOException("Unsupported perimeter mode."); }
        return budget.keep(repo.open(id,Constants.OBJ_BLOB).getCachedBytes(budget.fileLimit()));
    }

    private static byte[] perimeterDisk(Directory dir,String[] parts,int index,int limit) throws IOException
    {
        if (index+1 == parts.length) { return dir.readIfPresent(parts[index],limit); }
        try (var child = dir.childIfPresent(parts[index]))
        {
            return child == null ? null : perimeterDisk(child,parts,index+1,limit);
        }
    }

    private record GitEntries(String branch, String headId, Map<String, ObjectId> head, Map<String, ObjectId> index,
        Set<String> submodules) { }

    private static GitEntries readGitEntries(Repository repository, ObjectReader reader, ScopedProject project,
        String prefix, Budget budget) throws IOException
    {
        Map<String, ObjectId> head = new HashMap<>();
        Map<String, ObjectId> index = new HashMap<>();
        Set<String> submodules = new HashSet<>();
        String sourcePrefix = prefix + SOURCE;
        ObjectId commit;
        String branch;
        try (RevWalk revisions = new RevWalk(reader); TreeWalk walk = new TreeWalk(reader))
        {
            branch = repository.getFullBranch();
            commit = repository.resolve(Constants.HEAD);
            if (commit != null)
            {
                walk.addTree(revisions.parseCommit(commit).getTree());
                walk.setFilter(PathFilter.create(sourcePrefix));
                walk.setRecursive(true);
                while (walk.next())
                {
                    addGitPath(project, prefix, walk.getPathString(), walk.getFileMode(0), walk.getObjectId(0),
                        head, submodules);
                    budget.checkEntries(head.size() + submodules.size());
                }
            }
            DirCache cache = repository.readDirCache();
            for (int position = 0; position < cache.getEntryCount(); position++)
            {
                budget.checkTime();
                DirCacheEntry entry = cache.getEntry(position);
                String name = entry.getPathString();
                if (entry.getStage() != DirCacheEntry.STAGE_0
                    && project.grant().projectPathInRepository(prefix, name) != null)
                {
                    throw new UnmergedIndex();
                }
                addGitPath(project, prefix, name, entry.getFileMode(), entry.getObjectId(), index, submodules);
                budget.checkEntries(index.size() + submodules.size());
            }
        }
        catch (UnmergedIndex e)
        {
            throw new IOException("Resolve Git index conflicts in the snapshot scope and retry.");
        }
        catch (IOException | RuntimeException e)
        {
            throw gitUnavailable();
        }
        return new GitEntries(branch, commit == null ? null : commit.name(), head, index, submodules);
    }

    private static final class UnmergedIndex extends IOException
    {
        private static final long serialVersionUID = 1L;
    }

    private static void addGitPath(ScopedProject project, String prefix, String name, FileMode mode, ObjectId id,
        Map<String, ObjectId> entries, Set<String> submodules) throws IOException
    {
        if (FileMode.GITLINK.equals(mode))
        {
            if ((prefix + SOURCE + "/").startsWith(name + "/"))
            {
                throw WindowsSourceTree.unavailable();
            }
            if (name.startsWith(prefix + SOURCE + "/"))
            {
                submodules.add(name.substring(prefix.length()));
            }
            return;
        }
        if (!FileMode.REGULAR_FILE.equals(mode) && !FileMode.EXECUTABLE_FILE.equals(mode))
        {
            return;
        }
        String path = project.grant().projectPathInRepository(prefix, name);
        if (path != null && project.permits(path))
        {
            entries.put(path, id);
        }
    }

    private static List<Entry> materialize(ObjectReader reader, GitEntries git, Map<String, Version> disk,
        Budget budget) throws IOException
    {
        Set<String> paths = new TreeSet<>(git.head().keySet());
        paths.addAll(git.index().keySet());
        paths.addAll(disk.keySet());
        budget.checkEntries(paths.size());
        Map<ObjectId, Version> blobs = new HashMap<>();
        List<Entry> entries = new ArrayList<>(paths.size());
        for (String path : paths)
        {
            budget.checkTime();
            entries.add(new Entry(path, blob(reader, git.head().get(path), blobs, budget),
                blob(reader, git.index().get(path), blobs, budget), disk.get(path)));
        }
        return entries;
    }

    private static Version blob(ObjectReader reader, ObjectId id, Map<ObjectId, Version> cache, Budget budget)
        throws IOException
    {
        if (id == null)
        {
            return null;
        }
        Version result = cache.get(id);
        if (result == null)
        {
            byte[] bytes;
            try
            {
                bytes = reader.open(id, Constants.OBJ_BLOB).getBytes(budget.fileLimit());
            }
            catch (IOException | RuntimeException e)
            {
                throw gitUnavailable();
            }
            result = budget.keep(bytes);
            cache.put(id, result);
        }
        return result;
    }

    private static Rules globalRules(Repository repo, WindowsSourceTree local, Path root, Directory git)
        throws IOException
    {
        String excludesFile = repo.getConfig().getString("core", null, "excludesfile");
        Path path = null;
        if (excludesFile == null)
        {
            String configHome = System.getenv("XDG_CONFIG_HOME");
            if (configHome != null && !configHome.isBlank())
            {
                path = Path.of(configHome).resolve("git").resolve("ignore");
            }
            else if (repo.getFS().userHome() != null)
            {
                path = repo.getFS().userHome().toPath().resolve(".config").resolve("git").resolve("ignore");
            }
        }
        else if (excludesFile.startsWith("~/") && repo.getFS().userHome() != null)
        {
            path = repo.getFS().userHome().toPath().resolve(excludesFile.substring(2));
        }
        else
        {
            path = root.resolve(excludesFile);
        }
        Rules rules = null;
        if (path != null)
        {
            path = path.normalize();
            if (path.getParent() == null)
            {
                throw WindowsSourceTree.unavailable();
            }
            try (Directory parent = local.openDirectoryIfPresent(path.getParent().toString()))
            {
                if (parent != null)
                {
                    rules = Rules.add(null, "", parent.readIfPresent(path.getFileName().toString(), MAX_RULE_BYTES));
                }
            }
        }
        try (Directory info = git.childIfPresent("info"))
        {
            return info == null ? rules : Rules.add(rules, "", info.readIfPresent("exclude", MAX_RULE_BYTES));
        }
    }

    private record Rules(Rules parent, String prefix, IgnoreNode ignore)
    {
        static Rules add(Rules parent, String prefix, byte[] bytes) throws IOException
        {
            if (bytes == null)
            {
                return parent;
            }
            IgnoreNode node = new IgnoreNode();
            node.parse(new ByteArrayInputStream(bytes));
            return new Rules(parent, prefix, node);
        }

        boolean ignores(String path, boolean directory)
        {
            for (Rules rules = this; rules != null; rules = rules.parent)
            {
                Boolean ignored = rules.ignore.checkIgnored(path.substring(rules.prefix.length()), directory);
                if (ignored != null)
                {
                    return ignored;
                }
            }
            return false;
        }
    }

    private static final class DiskWalk
    {
        private final ScopedProject project;
        private final NavigableSet<String> tracked;
        private final Set<String> submodules;
        private final Budget budget;
        private final boolean forYaxunit;
        private final Map<String, Version> versions = new HashMap<>();
        private boolean attributes;

        DiskWalk(ScopedProject project, Set<String> tracked, Set<String> submodules, Budget budget, boolean forYaxunit)
        {
            this.project = project;
            this.tracked = new TreeSet<>(tracked);
            this.submodules = submodules;
            this.budget = budget;
            this.forYaxunit = forYaxunit;
        }

        Rules controls(Directory directory, String prefix, Rules rules, boolean ignored) throws IOException
        {
            attributes |= directory.readIfPresent(".gitattributes", MAX_RULE_BYTES) != null;
            return ignored ? rules : Rules.add(rules, prefix, directory.readIfPresent(".gitignore", MAX_RULE_BYTES));
        }

        void scan(Directory directory, String path, String repoPrefix, Rules parent, boolean parentIgnored, int depth)
            throws IOException
        {
            budget.checkTime();
            if (depth > MAX_DEPTH)
            {
                throw new IOException("Snapshot source exceeds the directory depth limit.");
            }
            List<WindowsSourceTree.Entry> contents = directory.entries(MAX_DIRECTORY_ENTRIES);
            for (WindowsSourceTree.Entry item : contents)
            {
                if (".git".equalsIgnoreCase(item.name()))
                {
                    if (forYaxunit)
                    {
                        throw SnapshotBuildInputs.unsupported();
                    }
                    return;
                }
            }
            Rules rules = controls(directory, repoPrefix + path, parent, parentIgnored);
            Set<String> names = new HashSet<>();
            for (WindowsSourceTree.Entry item : contents)
            {
                budget.checkEntries(++budget.diskEntries);
                String name = item.name();
                if (forYaxunit && (!names.add(name.toLowerCase(Locale.ROOT)) || item.reparsePoint()
                    || !safeBuildComponent(name)))
                {
                    throw SnapshotBuildInputs.unsupported();
                }
                if (!WindowsSourceTree.isComponent(name) || name.charAt(0) == '.')
                {
                    if (forYaxunit && (item.directory() || !Set.of(".gitignore", ".gitattributes").contains(name)))
                    {
                        throw SnapshotBuildInputs.unsupported();
                    }
                    continue;
                }
                String relative = path + name;
                if (submodules.contains(relative) || project.isWorkspaceLink(relative, item.directory()))
                {
                    if (forYaxunit)
                    {
                        throw SnapshotBuildInputs.unsupported();
                    }
                    continue;
                }
                boolean ignored = parentIgnored || rules != null && rules.ignores(repoPrefix + relative, item.directory());
                if (item.directory() && (forYaxunit || !ignored || hasTrackedChild(relative)))
                {
                    try (Directory child = directory.child(name))
                    {
                        scan(child, relative + "/", repoPrefix, rules, ignored, depth + 1);
                    }
                }
                else if (!item.directory() && project.grant().permits(relative)
                    && (forYaxunit || !ignored || tracked.contains(relative)))
                {
                    versions.put(relative, budget.keep(directory.read(name, budget.fileLimit())));
                }
                else if (forYaxunit)
                {
                    throw SnapshotBuildInputs.unsupported();
                }
            }
        }

        private boolean hasTrackedChild(String path)
        {
            String prefix = path + "/";
            String next = tracked.ceiling(prefix);
            return next != null && next.startsWith(prefix);
        }
    }

    static boolean safeBuildComponent(String name)
    {
        if (!WindowsSourceTree.isComponent(name))
        {
            return false;
        }
        int dot = name.indexOf('.');
        String stem = (dot < 0 ? name : name.substring(0, dot)).stripTrailing().toUpperCase(Locale.ROOT);
        return !stem.matches("CON|CONIN\\$|CONOUT\\$|PRN|AUX|NUL|COM[1-9\u00B9\u00B2\u00B3]|LPT[1-9\u00B9\u00B2\u00B3]");
    }

    private static final class Budget
    {
        private final long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        private long bytes;
        private int diskEntries;

        void checkTime() throws IOException
        {
            if (Thread.currentThread().isInterrupted() || System.nanoTime() - deadline >= 0)
            {
                throw new IOException("Snapshot capture exceeded its time budget or was interrupted.");
            }
        }

        void checkEntries(int count) throws IOException
        {
            checkTime();
            if (count > MAX_DIRECTORY_ENTRIES)
            {
                throw new IOException("Snapshot source exceeds the manifest entry limit.");
            }
        }

        int fileLimit()
        {
            return (int)Math.min(MAX_FILE_BYTES, MAX_CONTENT_BYTES - bytes);
        }

        Version keep(byte[] content)
        {
            bytes += content.length;
            return new Version(content);
        }
    }

    static String contentIdOf(byte[] bytes)
    {
        try
        {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        }
        catch (NoSuchAlgorithmException e)
        {
            throw new IllegalStateException("SHA-256 is unavailable.", e);
        }
    }

    private static IOException gitUnavailable()
    {
        return new IOException("Cannot capture the Git HEAD and index for this project. Check the local work tree and capture limits.");
    }
}
