/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils.git;

import java.io.File;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.egit.core.op.ConnectProviderOperation;
import org.eclipse.egit.core.project.RepositoryMapping;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.team.core.RepositoryProvider;

import com.ditrix.edt.mcp.server.Activator;

/**
 * Connects an imported project to its git repository with EGit - the headless
 * equivalent of GUI "Team &gt; Share Project" ({@link ConnectProviderOperation}),
 * so an {@code import_project} slot resolves to ITS OWN repository/branch instead
 * of falling through to the main checkout's repository (whose CURRENT branch then
 * wrongly owns the slot's infobase association context; see the 05.11 slot forensics:
 * a slot without the EGit team provider is resolved by workTree-PREFIX against the
 * main checkout's mapping).
 *
 * <p>The operation is the same one the GUI runs: it registers
 * {@code org.eclipse.egit.core.GitProvider} on the project and persists the
 * repository mapping in {@code GitProjectData.properties}. After it, EGit's
 * {@link RepositoryMapping#getMapping(IProject)} resolves the project to ITS OWN
 * repository (worktree or clone) and the branch-context association machinery sees
 * the slot's own branch.
 *
 * <p>Fail-open by design: the import itself must not fail because sharing could not
 * be completed (no .git anywhere above the project - e.g. a plain copy of the
 * project subdirectory without the repository - or an EGit error). The caller gets
 * a {@link Outcome#warning()} to surface instead.
 */
public final class GitConnectSupport
{
    /** Bounded background-Job timeout for the connect (it is a fast metadata write). */
    public static final long CONNECT_TIMEOUT_SECONDS = 30;

    private GitConnectSupport()
    {
        // Utility class
    }

    /** The outcome of a connect attempt (never throws). */
    public static final class Outcome
    {
        private final boolean connected;

        private final boolean alreadyShared;

        private final String repositoryPath;

        private final String branch;

        private final String warning;

        private Outcome(boolean connected, boolean alreadyShared, String repositoryPath,
            String branch, String warning)
        {
            this.connected = connected;
            this.alreadyShared = alreadyShared;
            this.repositoryPath = repositoryPath;
            this.branch = branch;
            this.warning = warning;
        }

        /** @return {@code true} when the project is EGit-shared after this call. */
        public boolean connected()
        {
            return connected;
        }

        /** @return {@code true} when it was shared before this call (no-op taken). */
        public boolean alreadyShared()
        {
            return alreadyShared;
        }

        /** @return absolute path of the repository the project was connected to, or {@code null}. */
        public String repositoryPath()
        {
            return repositoryPath;
        }

        /** @return current branch short name (best effort), or {@code null}. */
        public String branch()
        {
            return branch;
        }

        /** @return fail-open warning (why sharing was skipped/failed), or {@code null}. */
        public String warning()
        {
            return warning;
        }
    }

    /**
     * Shares the project with EGit if it is not shared yet and a git repository can
     * be found above (or at) its location. Idempotent: an already-shared project is
     * returned as {@link Outcome#alreadyShared()} with no mutation.
     *
     * @param project the imported/opened project (must exist)
     * @return the outcome, never {@code null}
     */
    public static Outcome connect(IProject project)
    {
        if (RepositoryProvider.getProvider(project) != null)
        {
            return describe(project, true, true, null);
        }

        File repoDir = findRepositoryDirectory(project);
        if (repoDir == null)
        {
            return new Outcome(false, false, null, null,
                "no .git found at or above the project location: the slot copy will fall back " //$NON-NLS-1$
                + "to the main checkout's git context for infobase associations. Copy the whole " //$NON-NLS-1$
                + "clone (including .git) or use a git worktree as the slot source."); //$NON-NLS-1$
        }

        try
        {
            new ConnectProviderOperation(project, repoDir)
                .execute(new NullProgressMonitor());
        }
        catch (Exception e)
        {
            Activator.logError("import_project: EGit connect failed for " + project.getName(), e); //$NON-NLS-1$
            return new Outcome(false, false, repoDir.getAbsolutePath(), null,
                "EGit connect failed (" + e.getClass().getSimpleName() //$NON-NLS-1$
                + "): " + safeMessage(e)); //$NON-NLS-1$
        }

        // Mapped now - refresh so the workspace notices the new team provider immediately.
        try
        {
            project.refreshLocal(IResource.DEPTH_ONE, new NullProgressMonitor());
        }
        catch (Exception e)
        {
            Activator.logError("import_project: refresh after EGit connect failed", e); //$NON-NLS-1$
        }
        return describe(project, true, false, repoDir.getAbsolutePath());
    }

    private static Outcome describe(IProject project, boolean connected, boolean alreadyShared,
        String repoPath)
    {
        String branch = null;
        if (connected)
        {
            // Best effort: the mapping's repository is EGit's cached instance, never closed here.
            try
            {
                RepositoryMapping mapping = RepositoryMapping.getMapping(project);
                if (mapping != null && mapping.getRepository() != null)
                {
                    branch = mapping.getRepository().getBranch();
                }
            }
            catch (Exception e)
            {
                Activator.logError("import_project: branch read after connect failed", e); //$NON-NLS-1$
            }
        }
        return new Outcome(connected, alreadyShared, repoPath, branch, null);
    }

    /**
     * Walks up from the project location to the filesystem root looking for a
     * {@code .git} directory (normal clone) or file (linked worktree). Returns the
     * directory CONTAINING the {@code .git} entry (the worktree root for worktrees),
     * or {@code null} when there is none.
     */
    static File findRepositoryDirectory(IProject project)
    {
        IPath location = project.getLocation();
        if (location == null)
        {
            return null;
        }
        File dir = location.toFile();
        while (dir != null)
        {
            File dotGit = new File(dir, ".git"); //$NON-NLS-1$
            if (dotGit.exists())
            {
                return dir;
            }
            dir = dir.getParentFile();
        }
        return null;
    }

    private static String safeMessage(Throwable t)
    {
        String m = t.getMessage();
        return (m != null && !m.isBlank()) ? m : t.getClass().getSimpleName();
    }
}
