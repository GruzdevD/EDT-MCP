/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IProject;

import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.McpKeys;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.utils.ApplicationSupport;
import com.ditrix.edt.mcp.server.utils.ProjectStateChecker;
import com.e1c.g5.dt.applications.ApplicationException;
import com.e1c.g5.dt.applications.IApplication;
import com.e1c.g5.dt.applications.IApplicationManager;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * Deletes an EDT application (the project-to-infobase binding) so it stops appearing in
 * {@code get_applications} for the project — WITHOUT removing the infobase itself.
 *
 * <p>This is the inverse of {@code create_project_application} (which mints a DISTINCT
 * application for a branch over an existing base): the same public EDT API the GUI uses,
 * {@link IApplicationManager#delete(IApplication, boolean)} with {@code unsynchronize=true}
 * (it cleans the application up, resets the project's default application and URL accesses,
 * and lets EDT's type delegate remove the association). Because only the association is
 * removed — {@code IInfobaseManager} is never touched — the underlying base stays registered
 * in {@code list_infobases} and on disk. That distinguishes this tool from
 * {@code delete_infobase}, which removes the base registration (and optionally its files).
 *
 * <p>Destructive to the application binding, but the base is untouched and the application is
 * re-creatable (via {@code create_project_application} / {@code set_branch_infobase}), so it
 * is guarded by a confirm-preview (mirroring {@code delete_infobase}): a bare call
 * (confirm omitted / false) reports what would be removed WITHOUT changing anything; only
 * {@code confirm=true} performs the deletion. Whether the application actually disappeared from
 * {@code get_applications} is verified by a bounded re-poll (read-back honesty, issue #412) and
 * reported, never assumed.
 */
public class DeleteProjectApplicationTool implements IMcpTool
{
    /** MCP tool name. */
    public static final String NAME = "delete_project_application"; //$NON-NLS-1$

    /** Output key: confirmation-required flag (true on a preview). */
    private static final String KEY_CONFIRMATION_REQUIRED = "confirmationRequired"; //$NON-NLS-1$

    /** Output value for {@link McpKeys#ACTION}: the application was removed. */
    private static final String VAL_DELETED = "deleted"; //$NON-NLS-1$

    /** Output key: whether the read-back confirmed the application is gone. */
    private static final String KEY_REMOVED = "removed"; //$NON-NLS-1$

    /** Output key: display name of the deleted application. */
    private static final String KEY_APPLICATION_NAME = "applicationName"; //$NON-NLS-1$

    /** Output key: evidence — the applications left after the deletion. */
    private static final String KEY_REMAINING_APPLICATIONS = "remainingApplications"; //$NON-NLS-1$

    /** Maximum bounded re-polls of get_applications used to confirm the application is gone. */
    private static final int READ_BACK_MAX_POLLS = 5;

    /** Delay between re-polls (ms). */
    private static final long READ_BACK_POLL_MS = 300L;

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Delete an EDT project application (a project-to-infobase binding) so it no longer " //$NON-NLS-1$
            + "appears in get_applications, WITHOUT removing the infobase itself (it stays in " //$NON-NLS-1$
            + "list_infobases and on disk). The inverse of create_project_application. Two-phase: call " //$NON-NLS-1$
            + "once WITHOUT confirm to preview, then again with confirm=true to apply. Parameters and " //$NON-NLS-1$
            + "examples: get_tool_guide('delete_project_application')."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty(McpKeys.PROJECT_NAME,
                "EDT configuration project whose application to delete (required).", true) //$NON-NLS-1$
            .stringProperty(McpKeys.APPLICATION_ID,
                "Application id from get_applications to delete (required).", true) //$NON-NLS-1$
            .booleanProperty("confirm", //$NON-NLS-1$
                "true = delete the application; default false = preview only (no change).") //$NON-NLS-1$
            .build();
    }

    @Override
    public String getOutputSchema()
    {
        return JsonSchemaBuilder.object()
            .booleanProperty("success", "Whether the operation succeeded", true) //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty(McpKeys.ACTION, "Either 'preview' (nothing changed) or 'deleted' (removed).") //$NON-NLS-1$
            .booleanProperty(KEY_CONFIRMATION_REQUIRED,
                "true on a preview (no change made); absent once deleted.") //$NON-NLS-1$
            .stringProperty(McpKeys.PROJECT, "Name of the configuration project.") //$NON-NLS-1$
            .stringProperty(McpKeys.APPLICATION_ID, "Application id that was deleted.") //$NON-NLS-1$
            .stringProperty(KEY_APPLICATION_NAME, "Display name of the deleted application.") //$NON-NLS-1$
            .booleanProperty(KEY_REMOVED,
                "Whether a read-back confirmed the application is gone from get_applications.") //$NON-NLS-1$
            .objectArrayProperty(KEY_REMAINING_APPLICATIONS,
                "Evidence: the applications left for the project after the deletion.") //$NON-NLS-1$
            .stringProperty(McpKeys.MESSAGE, "Human-readable status message.") //$NON-NLS-1$
            .build();
    }

    @Override
    public ResponseType getResponseType()
    {
        return ResponseType.JSON;
    }

    @Override
    public boolean connectsToInfobase()
    {
        // IApplicationManager.delete cleans the application up — and for a running standalone
        // server that reaches the application/infobase connection layer (issue #270's gate).
        return true;
    }

    @Override
    public String execute(Map<String, String> params)
    {
        String err = JsonUtils.requireArguments(params, McpKeys.PROJECT_NAME, McpKeys.APPLICATION_ID);
        if (err != null)
        {
            return err;
        }

        String projectName = JsonUtils.extractStringArgument(params, McpKeys.PROJECT_NAME);
        String applicationId = JsonUtils.extractStringArgument(params, McpKeys.APPLICATION_ID);
        boolean confirm = JsonUtils.extractBooleanArgument(params, "confirm", false); //$NON-NLS-1$

        // Refuse only the transient BUILDING state.
        String building = ProjectStateChecker.buildingErrorOrNull(projectName);
        if (building != null)
        {
            return ToolResult.error(building).toJson();
        }

        return deleteApplication(projectName, applicationId, confirm);
    }

    private String deleteApplication(String projectName, String applicationId, boolean confirm)
    {
        ApplicationSupport.ManagerResult mr = ApplicationSupport.resolveManager(projectName);
        if (!mr.ok())
        {
            return mr.errorJson();
        }
        IProject project = mr.project();
        IApplicationManager appManager = mr.manager();

        IApplication app = resolveApplication(appManager, project, applicationId);
        if (app == null)
        {
            return ToolResult.error("Application not found: " + applicationId //$NON-NLS-1$
                + ". Use get_applications to get valid application IDs.").toJson(); //$NON-NLS-1$
        }

        if (!confirm)
        {
            return buildPreviewResult(projectName, app);
        }

        try
        {
            // The canonical EDT deletion API: cleans the application up, resets the project
            // default / URL accesses and lets the application type's delegate remove the
            // association (unsynchronize=true). The base stays registered (we never touch
            // IInfobaseManager) - this is the inverse of create_project_application, not of
            // delete_infobase.
            appManager.delete(app, true);
        }
        catch (ApplicationException e)
        {
            Activator.logError("delete_project_application: delete failed for application '" //$NON-NLS-1$
                + applicationId + "' of project '" + projectName + "'", e); //$NON-NLS-1$ //$NON-NLS-2$
            return ToolResult.error("Failed to delete application '" + app.getName() + "' (" //$NON-NLS-1$
                + applicationId + ") of project '" + projectName //$NON-NLS-1$
                + "': " + e.getMessage()).toJson(); //$NON-NLS-1$
        }

        return buildDeletedResult(projectName, app, appManager, project);
    }

    /**
     * Resolves an application by id, or returns {@code null} (an absent application and a
     * resolution failure both read as "not found" so the single not-found path stays authoritative).
     */
    private static IApplication resolveApplication(IApplicationManager appManager, IProject project,
            String applicationId)
    {
        try
        {
            return appManager.getApplication(project, applicationId).orElse(null);
        }
        catch (ApplicationException e)
        {
            Activator.logError("delete_project_application: error resolving application '" //$NON-NLS-1$
                + applicationId + "'", e); //$NON-NLS-1$
            return null;
        }
    }

    private static String buildPreviewResult(String projectName, IApplication app)
    {
        return ToolResult.success()
            .put(McpKeys.ACTION, "preview") //$NON-NLS-1$
            .put(KEY_CONFIRMATION_REQUIRED, true)
            .put(McpKeys.PROJECT, projectName)
            .put(McpKeys.APPLICATION_ID, app.getId())
            .put(KEY_APPLICATION_NAME, app.getName())
            .put(McpKeys.MESSAGE, "PREVIEW: this would delete application '" + app.getName() //$NON-NLS-1$
                + "' (" + app.getId() + ") of project '" + projectName //$NON-NLS-1$
                + "'. The infobase itself is NOT removed - it stays in list_infobases and on " //$NON-NLS-1$
                + "disk. Use delete_infobase to also remove the base. Re-call with confirm=true " //$NON-NLS-1$
                + "to apply.").toJson(); //$NON-NLS-1$
    }

    /**
     * After {@code confirm=true}, verifies the application actually disappeared from
     * {@code get_applications} (bounded re-poll — read-back honesty). Reports {@code removed}
     * truthfully, with the remaining applications as evidence.
     */
    private static String buildDeletedResult(String projectName, IApplication app,
            IApplicationManager appManager, IProject project)
    {
        boolean gone = !appStillPresent(appManager, project, app.getId(), READ_BACK_MAX_POLLS);
        JsonArray remaining = readRemainingApplications(appManager, project);

        ToolResult result = ToolResult.success()
            .put(McpKeys.ACTION, VAL_DELETED)
            .put(McpKeys.PROJECT, projectName)
            .put(McpKeys.APPLICATION_ID, app.getId())
            .put(KEY_APPLICATION_NAME, app.getName())
            .put(KEY_REMOVED, gone)
            .put(McpKeys.MESSAGE, gone
                ? "Application '" + app.getName() + "' (" + app.getId() //$NON-NLS-1$ //$NON-NLS-2$
                    + ") was deleted from project '" + projectName //$NON-NLS-1$
                    + "'. The infobase itself is untouched (it stays in list_infobases)." //$NON-NLS-1$
                : "Application deletion for '" + app.getName() + "' was requested and raised no " //$NON-NLS-1$
                    + "error, but a read-back still shows '" + app.getId() //$NON-NLS-1$
                    + "' for project '" + projectName + "'. Use get_applications to check the " //$NON-NLS-1$ //$NON-NLS-2$
                    + "actual state (a running standalone server may need to be stopped first)."); //$NON-NLS-1$
        if (remaining != null)
        {
            result.put(KEY_REMAINING_APPLICATIONS, remaining);
        }
        return result.toJson();
    }

    /**
     * Returns {@code true} while the application id is still present, polling a bounded number of
     * times so an asynchronous EDT removal finishes. A read failure returns {@code true} (absence
     * is not established) — the honest default that keeps the caller from claiming removal.
     */
    private static boolean appStillPresent(IApplicationManager appManager, IProject project,
            String applicationId, int maxPolls)
    {
        for (int poll = 0; poll < maxPolls; poll++) // NOSONAR multiple intentional exits
        {
            List<IApplication> applications;
            boolean readOk;
            try
            {
                applications = appManager.getApplications(project);
                readOk = true;
            }
            catch (ApplicationException e)
            {
                Activator.logError("delete_project_application: get_applications read-back failed", e); //$NON-NLS-1$
                return true; // read failed — absence NOT established
            }
            if (readOk)
            {
                boolean present = false;
                if (applications != null)
                {
                    for (IApplication a : applications)
                    {
                        if (a != null && applicationId.equals(a.getId()))
                        {
                            present = true;
                            break;
                        }
                    }
                }
                if (!present)
                {
                    return false;
                }
            }
            if (poll < maxPolls - 1)
            {
                try
                {
                    Thread.sleep(READ_BACK_POLL_MS);
                }
                catch (InterruptedException ie)
                {
                    Thread.currentThread().interrupt();
                    return true;
                }
            }
        }
        return true;
    }

    /**
     * Renders the applications still listed for the project after the deletion ({@code id},
     * {@code name}) — or {@code null} when no read produced a snapshot (a failed read must not
     * look like an empty project).
     */
    private static JsonArray readRemainingApplications(IApplicationManager appManager, IProject project)
    {
        try
        {
            List<IApplication> applications =
                new ArrayList<>(appManager.getApplications(project));
            JsonArray array = new JsonArray();
            for (IApplication a : applications)
            {
                JsonObject o = new JsonObject();
                o.addProperty("id", a.getId()); //$NON-NLS-1$
                o.addProperty("name", a.getName()); //$NON-NLS-1$
                array.add(o);
            }
            return array;
        }
        catch (ApplicationException e)
        {
            Activator.logError("delete_project_application: read-back of remaining applications failed", e); //$NON-NLS-1$
            return null;
        }
    }
}
