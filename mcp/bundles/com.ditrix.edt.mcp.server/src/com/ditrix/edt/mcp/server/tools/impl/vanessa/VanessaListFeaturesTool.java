/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 *
 * edt-mcp-vanessa: list the Vanessa Automation feature files of a project or directory.
 */

package com.ditrix.edt.mcp.server.tools.impl.vanessa;

import java.io.IOException;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.IMcpTool;

/**
 * Recursively lists the {@code *.feature} files available for a project (or an
 * explicit directory), each with its {@code Feature:} headline and feature-level
 * tags — the inventory {@code vanessa_get_feature} then drills into. Pure file
 * work on the out-of-repo feature dir; no EDT model access.
 */
public class VanessaListFeaturesTool implements IMcpTool
{
    public static final String NAME = "vanessa_list_features"; //$NON-NLS-1$

    private static final String KEY_PROJECT = "project"; //$NON-NLS-1$
    private static final String KEY_DIR = "dir"; //$NON-NLS-1$

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "List the Vanessa Automation .feature files under a project (or an explicit directory): each " //$NON-NLS-1$
            + "entry carries the path, the Feature: headline and the feature-level tags, plus a count. Use a " //$NON-NLS-1$
            + "returned path with vanessa_get_feature or vanessa_run_feature. Parameters and examples: " //$NON-NLS-1$
            + "get_tool_guide('vanessa_list_features')."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty(KEY_PROJECT,
                "Project key whose FEATURES_DIR to scan, e.g. 'afm' (see ~/.1c-tools/vanessa/projects/<project>/env.sh).", //$NON-NLS-1$
                true)
            .stringProperty(KEY_DIR,
                "Absolute directory to scan instead of the project's FEATURES_DIR.") //$NON-NLS-1$
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
        String project = params.get(KEY_PROJECT);
        if (project == null || project.trim().isEmpty())
        {
            return ToolResult.error("project is required.").toJson(); //$NON-NLS-1$
        }

        VanessaProjectConfig config = VanessaProjectConfig.fromProject(project.trim());
        if (config == null)
        {
            return ToolResult.error(VanessaProjectConfig.notFoundMessage(project.trim())).toJson(); //$NON-NLS-1$
        }

        String dir = params.get(KEY_DIR);
        String root;
        if (dir != null && !dir.trim().isEmpty())
        {
            root = dir.trim();
        }
        else
        {
            root = config.defaultFeatureTarget();
        }

        List<Map<String, Object>> items = new ArrayList<>();
        try
        {
            for (java.nio.file.Path p : VanessaFeatureScanner.findFeatures(Paths.get(root)))
            {
                Map<String, Object> h = VanessaFeatureScanner.header(p);
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("path", p.toString()); //$NON-NLS-1$
                item.put("featureName", h.get("feature")); //$NON-NLS-1$
                item.put("tags", h.get("tags")); //$NON-NLS-1$
                items.add(item);
            }
        }
        catch (IOException e)
        {
            return ToolResult.error("Failed to scan feature dir '" + root + "': " + e.getMessage()).toJson(); //$NON-NLS-1$
        }

        return ToolResult.success()
            .put("root", root) //$NON-NLS-1$
            .put("count", items.size()) //$NON-NLS-1$
            .put("features", items) //$NON-NLS-1$
            .toJson();
    }
}
