/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;

/**
 * Imports a project that lives OUTSIDE the workspace tree as a linked project — the shared
 * replacement for the mechanics {@code McpServerStartup} used to inline and that the
 * VA_Runner provisioning path needs as well.
 * <p>
 * A normal Eclipse "generic import" copies the project in; here the project's {@code .project}
 * descriptor is read from its on-disk location and {@link IProjectDescription#setLocation(IPath)}
 * links it so EDT can open it without moving a file (a git checkout, an out-of-repo launcher…).
 * Mirroring the original, a missing {@code .project} is not an error — it just means "nothing to
 * import" — while a real create/open failure surfaces as a {@link CoreException}.
 */
public final class ProjectImportUtils
{
    private ProjectImportUtils()
    {
        // Utility class
    }

    /**
     * Imports (or merely opens, when already imported) the project described by
     * {@code <dir>/.project}, linked to {@code dir} outside the workspace tree.
     *
     * @param workspace the workspace to import into
     * @param dir the absolute on-disk directory holding the project's {@code .project}
     * @param monitor a progress monitor ({@code null}-allowed)
     * @return {@code true} when the project ended up present and open
     * @throws CoreException if the descriptor cannot be loaded or the project cannot be created/opened
     */
    public static boolean importLinkedProject(IWorkspace workspace, String dir, IProgressMonitor monitor)
        throws CoreException
    {
        IPath projectDir = IPath.fromOSString(dir);
        IPath descPath = projectDir.append(IProjectDescription.DESCRIPTION_FILE_NAME);
        if (!descPath.toFile().isFile())
        {
            return false;
        }

        IProjectDescription description = workspace.loadProjectDescription(descPath);
        IProject project = workspace.getRoot().getProject(description.getName());
        if (!project.exists())
        {
            description.setLocation(projectDir);
            project.create(description, monitor);
        }
        if (!project.isOpen())
        {
            project.open(monitor);
        }
        return project.isAccessible();
    }
}
