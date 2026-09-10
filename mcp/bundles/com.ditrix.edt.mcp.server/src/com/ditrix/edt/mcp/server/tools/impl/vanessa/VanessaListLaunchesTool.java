/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 *
 * edt-mcp-vanessa: list BDD (Vanessa Automation) launch configurations.
 */

package com.ditrix.edt.mcp.server.tools.impl.vanessa;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.ILaunchManager;

import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.IMcpTool;

/**
 * Lists the EDT launch configurations usable for a Vanessa Automation BDD run.
 * <p>
 * The caller typically finds here a configuration whose name matches the Vanessa
 * setup for a project, then passes it to {@code vanessa_run_feature}.
 */
public class VanessaListLaunchesTool implements IMcpTool
{
    public static final String NAME = "vanessa_list_launches"; //$NON-NLS-1$

    private static final String KEY_PROJECT_FILTER = "projectFilter"; //$NON-NLS-1$

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "List EDT launch configurations suitable for launching a Vanessa Automation BDD run " //$NON-NLS-1$
            + "(optionally filtered to a project). Returns each configuration's name, project and a " //$NON-NLS-1$
            + "'vanessa' hint (name looks like a BDD/vanessa config). Use the returned name with " //$NON-NLS-1$
            + "vanessa_run_feature. Parameters and examples: get_tool_guide('vanessa_list_launches')."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty(KEY_PROJECT_FILTER,
                "Optional: only list configurations belonging to a project whose name contains this.") //$NON-NLS-1$
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
        ILaunchManager manager = DebugPlugin.getDefault() == null
            ? null : DebugPlugin.getDefault().getLaunchManager();
        if (manager == null)
        {
            return ToolResult.error("Debug plugin is not available; EDT is still starting up.").toJson(); //$NON-NLS-1$
        }

        String projectFilter = params.get(KEY_PROJECT_FILTER);
        if (projectFilter != null)
        {
            projectFilter = projectFilter.trim();
            if (projectFilter.isEmpty())
            {
                projectFilter = null;
            }
        }

        List<Map<String, Object>> items = new ArrayList<>();
        try
        {
            ILaunchConfiguration[] configs = manager.getLaunchConfigurations();
            for (ILaunchConfiguration c : configs)
            {
                String project = null;
                try
                {
                    project = c.getAttribute(
                        "com._1c.g5.v8.dt.debug.core.ATTR_PROJECT_NAME", (String) null); //$NON-NLS-1$
                }
                catch (CoreException e)
                {
                    // Best effort: project attribute is not present on every config type.
                }
                String name = c.getName();
                if (projectFilter != null && (project == null || !project.contains(projectFilter)))
                {
                    // Match on name too, so a filter like "vanessa" still helps.
                    if (name == null || !name.toLowerCase().contains(projectFilter.toLowerCase()))
                    {
                        continue;
                    }
                }
                Map<String, Object> item = new java.util.LinkedHashMap<>();
                item.put("name", name); //$NON-NLS-1$
                item.put("project", project); //$NON-NLS-1$
                item.put("vanessa", isVanessaLike(name)); //$NON-NLS-1$
                items.add(item);
            }
        }
        catch (CoreException e)
        {
            return ToolResult.error("Failed to list launch configurations: " + e.getMessage()).toJson(); //$NON-NLS-1$
        }

        return ToolResult.success()
            .put("count", items.size()) //$NON-NLS-1$
            .put("launches", items) //$NON-NLS-1$
            .toJson();
    }

    /** Loose heuristic: mark a config as a Vanessa BDD config by its name. */
    static boolean isVanessaLike(String name)
    {
        if (name == null)
        {
            return false;
        }
        String lower = name.toLowerCase();
        return lower.contains("vanessa") || lower.contains("bdd") || lower.contains("vrunner"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }
}
