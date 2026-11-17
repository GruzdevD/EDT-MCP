/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.io.File;
import java.util.Map;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.IWorkspaceRunnable;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;

import com._1c.g5.v8.dt.core.platform.IDtProject;
import com._1c.g5.v8.dt.core.platform.IDtProjectManager;
import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.McpKeys;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.utils.LifecycleWaiter;
import com.ditrix.edt.mcp.server.utils.McpJobs;
import com.ditrix.edt.mcp.server.utils.ProjectContext;
import com.ditrix.edt.mcp.server.utils.ProjectImportUtils;
import com.ditrix.edt.mcp.server.utils.git.GitConnectSupport;

/**
 * Tool to register an EXISTING EDT project directory (one that already contains a
 * {@code .project} file - a git worktree, a second checkout, a copy) into the workspace
 * as a LINKED project.
 *
 * <p>The files stay where they are (nothing is copied or moved); the project opens and
 * builds like any workspace project. It is the counterpart of
 * {@link DeleteProjectTool} (cleanup) and complements
 * {@link CreateProjectTool} (which starts an EMPTY new project) and
 * {@link ImportConfigurationFromXmlTool} (which builds a project from a configuration
 * XML dump).
 *
 * <p>Typical use: parallel task slots - each task works in its own git worktree,
 * registered here under its own project name (optionally renamed via {@code projectName}
 * to avoid a name conflict with the main checkout).
 */
public class ImportProjectTool implements IMcpTool
{
    public static final String NAME = "import_project"; //$NON-NLS-1$

    /** Input param: absolute path to the directory containing .project. */
    private static final String KEY_PATH = "path"; //$NON-NLS-1$

    /** Input param: optional new name for the project in the workspace. */
    private static final String KEY_PROJECT_NAME = "projectName"; //$NON-NLS-1$

    /** Output field: absolute location the project was registered from. */
    private static final String KEY_LOCATION = "location"; //$NON-NLS-1$

    /** Output field: lifecycle state reached ('ready' or 'created'). */
    private static final String KEY_STATE = "state"; //$NON-NLS-1$

    /** Output field: whether the project was renamed in the workspace. */
    private static final String KEY_RENAMED = "renamed"; //$NON-NLS-1$

    /** Output field: whether the project is EGit-shared after this call. */
    private static final String KEY_GIT_CONNECTED = "gitConnected"; //$NON-NLS-1$

    /** Output field: absolute path of the repository the project is shared with (best effort). */
    private static final String KEY_GIT_REPOSITORY = "gitRepository"; //$NON-NLS-1$

    /** Output field: current branch short name of the shared repository (best effort). */
    private static final String KEY_GIT_BRANCH = "gitBranch"; //$NON-NLS-1$

    /** Output field: fail-open warning when EGit sharing was skipped/failed. */
    private static final String KEY_GIT_WARNING = "gitWarning"; //$NON-NLS-1$

    /** Join timeout for the import Job (mirrors create_project). */
    private static final long IMPORT_TIMEOUT_MS = 120_000L;

    /** Wait timeout for the DT lifecycle STARTED state (mirrors create_project). */
    private static final long STARTED_TIMEOUT_MS = 60_000L;

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Register an EXISTING EDT project directory (a git worktree, a second checkout, " //$NON-NLS-1$
            + "a copy) that already contains a .project file into the workspace as a LINKED " //$NON-NLS-1$
            + "project: files stay in place, nothing is copied or moved. Use it to give a parallel " //$NON-NLS-1$
            + "task its own copy of a configuration project (a slot): point path at the worktree " //$NON-NLS-1$
            + "directory, optionally rename it via projectName. The project is also EGit-shared " //$NON-NLS-1$
            + "(Team > Share Project equivalent) when a .git is found at or above the directory, " //$NON-NLS-1$
            + "so it resolves to its OWN repository/branch; result reports gitConnected, " //$NON-NLS-1$
            + "gitRepository, gitBranch and gitWarning. To start an EMPTY new project use " //$NON-NLS-1$
            + "create_project; to build a project from a configuration XML dump use " //$NON-NLS-1$
            + "import_configuration_from_xml. Parameters and examples: get_tool_guide('import_project')."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty(KEY_PATH,
                "ABSOLUTE path to the directory that contains .project (used in place, linked - not copied).", //$NON-NLS-1$
                true)
            .stringProperty(KEY_PROJECT_NAME,
                "Optional new name for the project in the workspace. Needed when the .project name " //$NON-NLS-1$
                + "already exists in the workspace (e.g. the main checkout is imported). The rename " //$NON-NLS-1$
                + "is workspace-level; the on-disk .project file is NOT rewritten.") //$NON-NLS-1$
            .build();
    }

    @Override
    public String getOutputSchema()
    {
        return JsonSchemaBuilder.object()
            .booleanProperty("success", "Whether the operation succeeded", true) //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty(McpKeys.ACTION,
                "One of 'imported' (registered and opened), 'opened' (existed closed, now open), " //$NON-NLS-1$
                + "'alreadyOpen' (nothing to do) or 'importing' (still running past the wait window).") //$NON-NLS-1$
            .stringProperty(McpKeys.PROJECT, "Workspace name of the project.") //$NON-NLS-1$
            .stringProperty(KEY_LOCATION, "Absolute directory the project is linked from.") //$NON-NLS-1$
            .stringProperty(KEY_STATE, "'ready' when lifecycle STARTED was reached, 'created' otherwise.") //$NON-NLS-1$
            .booleanProperty(KEY_RENAMED, "Whether projectName renamed the project.") //$NON-NLS-1$
            .booleanProperty(KEY_GIT_CONNECTED,
                "Whether the project is shared with EGit (Team > Share Project equivalent).") //$NON-NLS-1$
            .stringProperty(KEY_GIT_REPOSITORY,
                "Repository path the project is shared with, when known.") //$NON-NLS-1$
            .stringProperty(KEY_GIT_BRANCH, "Current branch of the shared repository, when known.") //$NON-NLS-1$
            .stringProperty(KEY_GIT_WARNING,
                "Present when EGit sharing was skipped or failed (fail-open).") //$NON-NLS-1$
            .stringProperty(McpKeys.MESSAGE, "Human-readable status message.") //$NON-NLS-1$
            .build();
    }

    @Override
    public ResponseType getResponseType()
    {
        return ResponseType.JSON;
    }

    @Override
    public String execute(Map<String, String> params)
    {
        String err = JsonUtils.requireArgument(params, KEY_PATH);
        if (err != null)
        {
            return err;
        }

        String pathValue = JsonUtils.extractStringArgument(params, KEY_PATH);
        String requestedName = JsonUtils.extractStringArgument(params, KEY_PROJECT_NAME);
        String targetName = (requestedName != null && !requestedName.isBlank()) ? requestedName.trim() : null;

        // Pure-filesystem validation FIRST: it needs no workspace access and gives a better
        // error than the CoreException workspace.loadProjectDescription would throw.
        if (pathValue == null || pathValue.isBlank())
        {
            return ToolResult.error("path is required: an ABSOLUTE directory path that contains a .project file.").toJson(); //$NON-NLS-1$
        }
        File dir = new File(pathValue.trim());
        String dirPath = dir.getAbsolutePath();
        if (!dir.isAbsolute())
        {
            return ToolResult.error("path must be an ABSOLUTE directory path, got: '" + pathValue + "'.").toJson(); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (!dir.isDirectory())
        {
            return ToolResult.error("No such directory: '" + dirPath + "'.").toJson(); //$NON-NLS-1$ //$NON-NLS-2$
        }
        File descFile = new File(dir, IProjectDescription.DESCRIPTION_FILE_NAME);
        if (!descFile.isFile())
        {
            return ToolResult.error("No .project file in '" + dirPath //$NON-NLS-1$
                + "'. import_project registers an EXISTING Eclipse/EDT project directory; to start an " //$NON-NLS-1$
                + "EMPTY new project use create_project, to build one from a configuration XML dump use " //$NON-NLS-1$
                + "import_configuration_from_xml.").toJson(); //$NON-NLS-1$
        }

        IWorkspace workspace = ResourcesPlugin.getWorkspace();
        IPath descPath = IPath.fromOSString(dirPath).append(IProjectDescription.DESCRIPTION_FILE_NAME);
        IProjectDescription description;
        try
        {
            description = workspace.loadProjectDescription(descPath);
        }
        catch (CoreException e)
        {
            return ToolResult.error("'" + descFile + "' is not a loadable Eclipse project descriptor: " //$NON-NLS-1$ //$NON-NLS-2$
                + e.getMessage()).toJson();
        }

        String effectiveName = (targetName != null) ? targetName : description.getName();
        boolean renamed = (targetName != null && !targetName.equals(description.getName()));

        // Name-collision check BEFORE mutating anything: a project with the effective name
        // at a DIFFERENT location is an actionable error; at the SAME location it is idempotent.
        ProjectContext ctx = ProjectContext.of(effectiveName);
        String action;
        if (ctx.exists())
        {
            String existingLocation = (ctx.project().getLocation() != null)
                ? ctx.project().getLocation().toOSString() : ""; //$NON-NLS-1$
            if (!existingLocation.equals(dirPath))
            {
                return ToolResult.error("Project '" + effectiveName + "' already exists in the workspace at '" //$NON-NLS-1$ //$NON-NLS-2$
                    + existingLocation + "'. Pass a different projectName, or remove the existing " //$NON-NLS-1$
                    + "project first (delete_project).").toJson(); //$NON-NLS-1$
            }
            if (ctx.isOpen())
            {
                GitConnectSupport.Outcome git = GitConnectSupport.connect(ctx.project());
                String state = waitForLifecycle(effectiveName);
                return successResult("alreadyOpen", effectiveName, dirPath, state, false, git,
                    "Project '" + effectiveName + "' is already registered from '" + dirPath + "' and open."); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            }
            action = "opened"; //$NON-NLS-1$
        }
        else
        {
            action = "imported"; //$NON-NLS-1$
        }

        final Throwable[] errorHolder = new Throwable[1];
        Job importJob = new Job("import_project: " + effectiveName) //$NON-NLS-1$
        {
            @Override
            protected IStatus run(IProgressMonitor monitor)
            {
                try
                {
                    IWorkspaceRunnable operation = opMonitor -> ProjectImportUtils.importLinkedProject(
                        ResourcesPlugin.getWorkspace(), dirPath, effectiveName, opMonitor);
                    workspace.run(operation, workspace.getRoot(), IWorkspace.AVOID_UPDATE, monitor);
                    ProjectContext imported = ProjectContext.of(effectiveName);
                    if (imported.exists())
                    {
                        imported.project().refreshLocal(IResource.DEPTH_INFINITE, monitor);
                    }
                }
                catch (Throwable t)
                {
                    errorHolder[0] = t;
                }
                return Status.OK_STATUS;
            }
        };
        importJob.setUser(false);
        McpJobs.schedule(importJob);

        try
        {
            importJob.join(IMPORT_TIMEOUT_MS, new NullProgressMonitor());
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            return ToolResult.errorWithUnknownMutationOutcome(
                "Project import was interrupted.").toJson(); //$NON-NLS-1$
        }

        // Slow path: still running past the wait window.
        if (importJob.getState() != Job.NONE)
        {
            if (ProjectContext.of(effectiveName).exists())
            {
                return successResult("importing", effectiveName, dirPath, "created", renamed, null,
                    "Import of '" + effectiveName + "' is still running past the " //$NON-NLS-1$ //$NON-NLS-2$
                    + (IMPORT_TIMEOUT_MS / 1000) + " second wait window; check list_projects."); //$NON-NLS-1$
            }
            importJob.cancel();
            return ToolResult.errorWithUnknownMutationOutcome(
                "Project import timed out after " + (IMPORT_TIMEOUT_MS / 1000) //$NON-NLS-1$
                + " seconds; the project may still appear shortly. If it does and is unwanted, " //$NON-NLS-1$
                + "remove it with delete_project.").toJson(); //$NON-NLS-1$
        }

        if (errorHolder[0] != null)
        {
            Activator.logError("Error importing project from " + dirPath, errorHolder[0]); //$NON-NLS-1$
            String message = "Failed to import project '" + effectiveName + "' from '" + dirPath //$NON-NLS-1$ //$NON-NLS-2$
                + "': " + errorHolder[0].getMessage(); //$NON-NLS-1$
            return (ProjectContext.of(effectiveName).exists()
                ? ToolResult.errorAfterMutation(message)
                : ToolResult.errorWithUnknownMutationOutcome(message)).toJson();
        }

        // Share with EGit right after the workspace registration: this is what makes the
        // project resolve to ITS OWN repository/branch (Team > Share Project equivalent).
        // Fail-open - a sharing problem never fails the import, it is reported as gitWarning.
        GitConnectSupport.Outcome git = GitConnectSupport.connect(ProjectContext.of(effectiveName).project());

        String state = waitForLifecycle(effectiveName);
        String verb = "imported".equals(action) ? "registered and opened" : "opened"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        String message = "Project '" + effectiveName + "' " + verb + " from '" + dirPath + "'." //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            + (renamed ? " (renamed from '" + description.getName() + "')" : ""); //$NON-NLS-1$ //$NON-NLS-2$
        if (git.warning() != null)
        {
            message += " git: " + git.warning(); //$NON-NLS-1$
        }
        return successResult(action, effectiveName, dirPath, state, renamed, git, message);
    }

    /**
     * Waits for the imported project to reach lifecycle STARTED.
     *
     * @param projectName the workspace project name
     * @return {@code "ready"} when STARTED was reached; {@code "created"} otherwise
     */
    private static String waitForLifecycle(String projectName)
    {
        try
        {
            IDtProjectManager dtProjectManager = Activator.getDefault().getDtProjectManager();
            ProjectContext ctx = ProjectContext.of(projectName);
            IProject project = ctx.exists() ? ctx.project() : null;
            if (dtProjectManager != null && project != null && project.exists())
            {
                IDtProject dtProject = dtProjectManager.getDtProject(project);
                if (dtProject != null
                    && LifecycleWaiter.waitForProjectStarted(dtProject, STARTED_TIMEOUT_MS))
                {
                    return "ready"; //$NON-NLS-1$
                }
            }
        }
        catch (Exception e)
        {
            Activator.logError("import_project: lifecycle wait error", e); //$NON-NLS-1$
        }
        return "created"; //$NON-NLS-1$
    }

    /** Builds the success JSON response for all non-error outcomes. */
    private static String successResult(String action, String projectName, String location,
        String state, boolean renamed, GitConnectSupport.Outcome git, String message)
    {
        ToolResult result = ToolResult.success()
            .put(McpKeys.ACTION, action)
            .put(McpKeys.PROJECT, projectName)
            .put(KEY_LOCATION, location)
            .put(KEY_STATE, state)
            .put(KEY_RENAMED, renamed)
            .put(McpKeys.MESSAGE, message);
        if (git != null)
        {
            result = result.put(KEY_GIT_CONNECTED, git.connected())
                .put(KEY_GIT_REPOSITORY, git.repositoryPath())
                .put(KEY_GIT_BRANCH, git.branch())
                .put(KEY_GIT_WARNING, git.warning());
        }
        return result.toJson();
    }
}
