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
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.NullProgressMonitor;

import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.McpKeys;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.utils.ProjectContext;
import com.ditrix.edt.mcp.server.utils.StandaloneServerSupport;
import com.ditrix.edt.mcp.server.utils.StandaloneServerSupport.RegistryCleanup;
import com.ditrix.edt.mcp.server.utils.StandaloneServerSupport.ServerInfo;
import com.e1c.g5.dt.applications.IApplicationManager;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * Removes the WST STANDALONE-server wiring that makes an EDT project treat an infobase
 * as an autonomous (standalone) server — the inverse of binding a base as one.
 *
 * <p>Why this exists: a base that mom's project config registers as an autonomous server
 * (see {@code com.e1c.g5.v8.dt.platform.standaloneserver.wst.core/infobases.yaml}) is surfaced
 * by the WST-server application provider, NOT by the normal infobase provider — so no normal
 * {@code com.e1c.g5.dt.applications.type.infobase} application can materialize for it while the
 * wiring stands (regardless of {@code create_project_application} / {@code create_infobase}).
 * Deleting those standalone servers rolls the base back to a plain file infobase, after which a
 * normal application can be created.
 *
 * <p>Deletion is via the same reflective {@code IStandaloneServerService} path
 * ({@link StandaloneServerSupport#deleteServer}) the standalone-server branch of
 * {@code create_infobase} / {@code delete_infobase} uses, plus the best-effort infobases.yaml
 * cleanup. Two-phase (mirroring {@code delete_infobase}): without {@code confirm} it previews the
 * matched servers; {@code confirm=true} deletes them. The served DATABASE is never touched — only
 * the server registration and config folder.
 */
public class RemoveStandaloneServerTool implements IMcpTool
{
    public static final String NAME = "remove_standalone_server"; //$NON-NLS-1$

    /** Input param: name of the infobase whose standalone-server wiring to remove. */
    private static final String KEY_INFOBASE_NAME = "infobaseName"; //$NON-NLS-1$

    /** Input param: {@code true} performs the deletion; false/omitted previews (default). */
    private static final String KEY_CONFIRM = "confirm"; //$NON-NLS-1$

    /** Output key: matched standalone server names. */
    private static final String KEY_MATCHED = "matchedServers"; //$NON-NLS-1$

    /** Output key: all standalone servers currently known to EDT (evidence/diagnosis). */
    private static final String KEY_ALL_SERVERS = "allServers"; //$NON-NLS-1$

    /** Output key: standalone servers still present after the removal. */
    private static final String KEY_REMAINING = "remainingServers"; //$NON-NLS-1$

    /** Output key: standalone servers actually removed (the deletion result). */
    private static final String KEY_REMOVED = "removedServers"; //$NON-NLS-1$

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Remove the WST STANDALONE-server wiring that makes an EDT project treat an infobase " //$NON-NLS-1$
            + "as an autonomous server, so a NORMAL infobase application can be created for it. A base " //$NON-NLS-1$
            + "wired as a standalone server is surfaced by the server provider, never as a plain " //$NON-NLS-1$
            + "type.infobase app. Destructive (deletes the standalone server + config), two-phase with " //$NON-NLS-1$
            + "confirm; the served DATABASE is never touched. Parameters and examples: " //$NON-NLS-1$
            + "get_tool_guide('remove_standalone_server')."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty(McpKeys.PROJECT_NAME,
                "EDT project whose standalone-server wiring for the infobase to remove (required).", //$NON-NLS-1$
                true)
            .stringProperty(KEY_INFOBASE_NAME,
                "Name of the infobase (from list_infobases) whose standalone-server wiring to remove " //$NON-NLS-1$
                    + "(required).", true) //$NON-NLS-1$
            .booleanProperty(KEY_CONFIRM,
                "true = delete the standalone server(s) and clean the registry; false/omitted = preview " //$NON-NLS-1$
                    + "only (default).") //$NON-NLS-1$
            .build();
    }

    @Override
    public String getOutputSchema()
    {
        return JsonSchemaBuilder.object()
            .booleanProperty("success", "Whether the operation succeeded", true) //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty(McpKeys.PROJECT, "Target EDT project name.") //$NON-NLS-1$
            .stringProperty(KEY_INFOBASE_NAME, "The infobase name targeted.") //$NON-NLS-1$
            .booleanProperty("confirmationRequired", //$NON-NLS-1$
                "Present on a preview: true (pass confirm=true to actually delete).")
            .objectArrayProperty(KEY_MATCHED,
                "The standalone server(s) matched to the infobase (name, infobaseId).")
            .objectArrayProperty(KEY_ALL_SERVERS,
                "All standalone servers currently known to EDT — evidence of what the removal considered.")
            .objectArrayProperty(KEY_REMOVED,
                "The standalone server(s) actually deleted (a deleted call).")
            .objectArrayProperty(KEY_REMAINING,
                "Standalone server(s) still present after a deletion.")
            .stringProperty(McpKeys.MESSAGE, "Human-readable status.")
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
        // A registry/metadata-only operation: it deletes WST server registrations and the infobases.yaml
        // entry, but opens NO infobase connection. Do not arm the auth-dialog suppressor.
        return false;
    }

    @Override
    public String execute(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, McpKeys.PROJECT_NAME);
        if (projectName == null || projectName.isEmpty())
        {
            return ToolResult.error("projectName is required (the EDT project whose standalone-server "
                + "wiring to remove). Use list_projects to see available projects.").toJson(); //$NON-NLS-1$
        }
        String infobaseName = JsonUtils.extractStringArgument(params, KEY_INFOBASE_NAME);
        if (infobaseName == null || infobaseName.isEmpty())
        {
            return ToolResult.error("infobaseName is required (the infobase whose standalone-server "
                + "wiring to remove). Use list_infobases to see the registered bases.").toJson(); //$NON-NLS-1$
        }
        boolean confirm = JsonUtils.extractBooleanArgument(params, KEY_CONFIRM, false);

        ProjectContext ctx = ProjectContext.of(projectName);
        if (!ctx.exists())
        {
            return ToolResult.error(ProjectContext.notFoundMessage(projectName)).toJson();
        }
        if (!ctx.isOpen())
        {
            return ToolResult.error("Project is closed: " + projectName //$NON-NLS-1$
                + ". Open the project in EDT first.").toJson(); //$NON-NLS-1$
        }
        Object service = StandaloneServerSupport.acquireService();
        IProject project = ctx.project();
        if (service == null)
        {
            return ToolResult.error("The EDT standalone-server feature is not available (bundle '"
                + "com.e1c.g5.v8.dt.platform.standaloneserver.wst.core' not present). Nothing was "
                + "removed.").toJson(); //$NON-NLS-1$
        }
        return remove(ctx, project, projectName, infobaseName, confirm, service);
    }

    private String remove(ProjectContext ctx, IProject project, String projectName, String infobaseName,
            boolean confirm, Object service)
    {
        // The PERSISTED standalone-server registrations (infobases.yaml via the delegate's modules map),
        // not getServers() — the servers need not be running for the wiring to block a normal app.
        List<ServerInfo> all = StandaloneServerSupport.listRegisteredServers();
        List<ServerInfo> matched = new ArrayList<>();
        for (ServerInfo info : all)
        {
            if (matches(info, infobaseName, projectName))
            {
                matched.add(info);
            }
        }
        JsonArray allArr = render(all);

        if (!confirm)
        {
            JsonArray matchedArr = render(matched);
            return ToolResult.success()
                .put(McpKeys.PROJECT, projectName)
                .put(KEY_INFOBASE_NAME, infobaseName)
                .put("confirmationRequired", true) //$NON-NLS-1$
                .put(KEY_MATCHED, matchedArr)
                .put(KEY_ALL_SERVERS, allArr)
                .put(McpKeys.MESSAGE, matched.isEmpty()
                    ? "No standalone server wiring found for infobase '" + infobaseName //$NON-NLS-1$
                        + "' in project '" + projectName + "' — nothing to remove. Call remove_standalone_server "
                        + "without confirm after listing, or check EDT's standalone servers. " //$NON-NLS-1$
                        + "All servers currently known to EDT are in allServers."
                    : "Preview: deleting " + matched.size() + " standalone server(s) backing infobase '" //$NON-NLS-1$
                        + infobaseName + "' makes it a NORMAL infobase again. The served DATABASE is not "
                        + "touched. Pass confirm=true to perform the deletion.")
                .toJson();
        }

        // Confirm: remove each matched entry from the persisted infobases.yaml registry. The servers are
        // not running (no live IServer), so there is nothing to stop — the registry removal is the whole
        // operation; EDT drops the corresponding server application on the next workbench start.
        IProgressMonitor monitor = new NullProgressMonitor();
        JsonArray removedArr = new JsonArray();
        List<String> failures = new ArrayList<>();
        for (ServerInfo info : matched)
        {
            JsonObject rec = new JsonObject();
            rec.addProperty("name", info.moduleName != null ? info.moduleName
                : (info.serverName != null ? info.serverName : "?")); //$NON-NLS-1$
            if (info.serverName != null)
            {
                rec.addProperty("serverName", info.serverName); //$NON-NLS-1$
            }
            if (info.infobaseId != null)
            {
                rec.addProperty("infobaseId", info.infobaseId); //$NON-NLS-1$
            }
            try
            {
                RegistryCleanup cleanup =
                    StandaloneServerSupport.removeFromInfobaseRegistry(info.module, info.infobaseId, monitor);
                if (cleanup == RegistryCleanup.FAILED)
                {
                    Activator.logInfo("remove_standalone_server: infobases.yaml entry for '" //$NON-NLS-1$
                        + info.moduleName + "' not cleaned (self-heals on restart)");
                }
                rec.addProperty("cleanup", cleanup.name()); //$NON-NLS-1$
                removedArr.add(rec);
            }
            catch (Exception e) // NOSONAR per-server failure must not abort the remaining removals
            {
                Activator.logError("remove_standalone_server: removal of server '" + info.serverName //$NON-NLS-1$
                    + "' failed", e); //$NON-NLS-1$
                failures.add(info.serverName + ": " + e.getMessage()); //$NON-NLS-1$
            }
        }
        List<ServerInfo> remaining = StandaloneServerSupport.listRegisteredServers();

        String status = "Removed " + removedArr.size() + " standalone server(s) backing infobase '" //$NON-NLS-1$
            + infobaseName + "' in project '" + projectName + "'. The infobase is now a NORMAL base; "
            + "create its application with create_infobase (mode=register, applicationKind=infobase) or "
            + "create_project_application.";
        if (!failures.isEmpty())
        {
            status += " Failed to remove: " + String.join("; ", failures) + "."; //$NON-NLS-1$ //$NON-NLS-2$
        }
        return ToolResult.success()
            .put(McpKeys.PROJECT, projectName)
            .put(KEY_INFOBASE_NAME, infobaseName)
            .put(KEY_REMOVED, removedArr)
            .put(KEY_REMAINING, render(remaining))
            .put(KEY_ALL_SERVERS, render(remaining))
            .put(McpKeys.MESSAGE, status)
            .toJson();
    }

    /**
     * Whether a server backs the target infobase name: its module display name equals the infobase
     * name (case-insensitive), or its raw infobaseId equals the target name (a low-fidelity fallback for
     * a server whose module name is generic). Never throws.
     */
    static boolean matches(ServerInfo info, String infobaseName, String projectName)
    {
        if (info == null)
        {
            return false;
        }
        if (info.moduleName != null && info.moduleName.equalsIgnoreCase(infobaseName))
        {
            return true;
        }
        return info.infobaseId != null && info.infobaseId.equalsIgnoreCase(infobaseName);
    }

    /** Renders {@link ServerInfo} list as the evidence arrays ({@code name}, {@code serverName}, {@code infobaseId}). */
    private static JsonArray render(List<ServerInfo> infos)
    {
        JsonArray arr = new JsonArray();
        if (infos == null)
        {
            return arr;
        }
        for (ServerInfo info : infos)
        {
            if (info == null)
            {
                continue;
            }
            JsonObject o = new JsonObject();
            o.addProperty("name", info.moduleName != null ? info.moduleName : (info.serverName != null ? info.serverName : "?")); //$NON-NLS-1$ //$NON-NLS-2$
            if (info.serverName != null)
            {
                o.addProperty("serverName", info.serverName); //$NON-NLS-1$
            }
            if (info.infobaseId != null)
            {
                o.addProperty("infobaseId", info.infobaseId); //$NON-NLS-1$
            }
            arr.add(o);
        }
        return arr;
    }
}
