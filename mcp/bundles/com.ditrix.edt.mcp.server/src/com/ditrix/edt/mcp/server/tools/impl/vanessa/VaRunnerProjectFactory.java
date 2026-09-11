/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 *
 * edt-mcp-vanessa: turnkey creation of the VA_Runner external-object project.
 */

package com.ditrix.edt.mcp.server.tools.impl.vanessa;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.IWorkspaceRunnable;
import org.eclipse.core.resources.ResourcesPlugin;

import com.ditrix.edt.mcp.server.utils.BackgroundJobs;
import com.ditrix.edt.mcp.server.utils.ProjectImportUtils;

/**
 * Turnkey creation of the {@code VA_Runner} external-objects project for a 1C project — the
 * button companion to the {@code vanessa_setup} tools. The on-disk tree
 * ({@code ~/.1c-tools/vanessa/launchers/<baseProject>/VA_Runner}) is assembled off the UI
 * thread in a background job by {@link VanessaBootstrap} (driver + boilerplate from the bundled
 * {@code templates/va_runner/}, the operator's {@code VanessaAutomation} EDT source, and the
 * VA runtime {@code bin/} from the shared release cache), then imported into the EDT workspace
 * as a linked project via {@link ProjectImportUtils#importLinkedProject}.
 *
 * <p>The {@link #provision(Path, String, Path)} step is pure (paths only, no workspace) so the
 * layout can be unit-tested without EDT; only the workspace import (and the shared runtime
 * download) touch the platform and network.</p>
 */
public final class VaRunnerProjectFactory
{
    /** Default owning-tool id used for {@link BackgroundJobs} jobs started from the UI. */
    public static final String DEFAULT_OWNER = "va_runner_create"; //$NON-NLS-1$

    /** Budget for one project creation (covers the ~30 MB runtime download). */
    private static final long TIMEOUT_MS = 10L * 60 * 1000;

    private static final String LAUNCHERS_REL = ".1c-tools/vanessa/launchers"; //$NON-NLS-1$

    /** base project -> "" marker for in-flight creations (dedup). */
    private static final ConcurrentMap<String, String> RUNS = new ConcurrentHashMap<>();

    private VaRunnerProjectFactory()
    {
        // Utility class
    }

    /**
     * Default on-disk location for a project's VA_Runner tree —
     * {@code ~/.1c-tools/vanessa/launchers/<baseProject>/VA_Runner}, matching the manual layout.
     *
     * @param baseProject the base configuration project key
     * @return the project root path (never {@code null})
     */
    public static Path defaultProjectRoot(String baseProject)
    {
        return Paths.get(System.getProperty("user.home"), //$NON-NLS-1$
            LAUNCHERS_REL, baseProject, "VA_Runner").normalize(); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Assembles the VA_Runner project tree at {@code destRoot}. Pure/path-based (no EDT
     * workspace); the only external dependency is the shared VA runtime cache, which is
     * downloaded lazily on first use.
     *
     * @param destRoot the project root to create
     * @param baseProject the base configuration project name ({@code Base-Project})
     * @param sourceTemplateDir the operator's {@code VanessaAutomation} EDT-source template dir
     * @return the created project root
     * @throws java.io.IOException on any provisioning failure
     */
    public static Path provision(Path destRoot, String baseProject, Path sourceTemplateDir)
        throws java.io.IOException
    {
        VanessaBootstrap.writeVaRunnerBoilerplate(destRoot, baseProject);
        VanessaBootstrap.copyVanessaAutomationSource(sourceTemplateDir, destRoot);
        VanessaBootstrap.provisionVanessaBin(destRoot);
        return destRoot;
    }

    /**
     * Starts a background job that provisions the VA_Runner project for {@code baseProject} and
     * imports it into the EDT workspace as a linked project. Deduplicated per base project. Runs
     * entirely off the UI thread ({@link BackgroundJobs}); poll the returned snapshot to observe
     * progress.
     *
     * @param owningTool the tool/UI id owning the job
     * @param baseProject the base configuration project key
     * @param destRoot where to create the project tree
     * @param sourceTemplateDir the operator's {@code VanessaAutomation} EDT-source template dir
     * @return the started job snapshot, or {@code null} when a creation is already in flight
     */
    public static BackgroundJobs.JobSnapshot create(String owningTool, String baseProject,
        Path destRoot, Path sourceTemplateDir)
    {
        if (baseProject == null || baseProject.isEmpty())
        {
            throw new IllegalArgumentException("baseProject must name a project"); //$NON-NLS-1$
        }
        if (RUNS.putIfAbsent(baseProject, "") != null) //$NON-NLS-1$
        {
            return null;
        }
        try
        {
            BackgroundJobs.JobSnapshot job = BackgroundJobs.shared()
                .start(owningTool, TIMEOUT_MS,
                    "Creating VA_Runner project for " + baseProject, progress -> { //$NON-NLS-1$
                        try
                        {
                            progress.add("Writing driver + boilerplate");
                            VanessaBootstrap.writeVaRunnerBoilerplate(destRoot, baseProject);
                            progress.add("Copying VanessaAutomation EDT source");
                            VanessaBootstrap.copyVanessaAutomationSource(sourceTemplateDir, destRoot);
                            progress.add("Provisioning VA runtime bin/");
                            VanessaBootstrap.provisionVanessaBin(destRoot);
                            progress.add("Importing VA_Runner into workspace");
                            importIntoWorkspace(destRoot);
                            return "Created VA_Runner project for " + baseProject //$NON-NLS-1$
                                + " at " + destRoot; //$NON-NLS-1$
                        }
                        finally
                        {
                            RUNS.remove(baseProject);
                        }
                    });
            return job;
        }
        catch (RuntimeException e)
        {
            RUNS.remove(baseProject);
            throw e;
        }
    }

    /** Whether a creation for the base project is currently in flight. */
    public static boolean isCreating(String baseProject)
    {
        return RUNS.containsKey(baseProject);
    }

    /** Imports the on-disk project root as a linked project into the EDT workspace. */
    private static void importIntoWorkspace(Path destRoot) throws Exception
    {
        IWorkspace workspace = ResourcesPlugin.getWorkspace();
        IWorkspaceRunnable op = monitor ->
            ProjectImportUtils.importLinkedProject(workspace, destRoot.toString(), monitor); //$NON-NLS-1$
        workspace.run(op, workspace.getRoot(), IWorkspace.AVOID_UPDATE, null);
    }
}
