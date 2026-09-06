/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 *
 * edt-mcp-vanessa: read and parse one Vanessa Automation feature file.
 */

package com.ditrix.edt.mcp.server.tools.impl.vanessa;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.IMcpTool;

/**
 * Reads and lightly parses a single {@code .feature} file: the {@code Feature:}
 * headline, feature-level tags, each scenario's name/tags/steps plus the raw
 * source. Complements {@code vanessa_list_features} (which returns the paths
 * this tool consumes). Pure file work; no EDT model access.
 */
public class VanessaGetFeatureTool implements IMcpTool
{
    public static final String NAME = "vanessa_get_feature"; //$NON-NLS-1$

    private static final String KEY_PATH = "path"; //$NON-NLS-1$

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Read and parse a Vanessa Automation .feature file: the Feature headline, feature-level tags, " //$NON-NLS-1$
            + "each scenario's name/tags/steps, and the raw source. 'path' is an absolute path from " //$NON-NLS-1$
            + "vanessa_list_features. Parameters and examples: get_tool_guide('vanessa_get_feature')."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty(KEY_PATH,
                "Absolute path to the .feature file to read (from vanessa_list_features).", //$NON-NLS-1$
                true)
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
        String path = params.get(KEY_PATH);
        if (path == null || path.trim().isEmpty())
        {
            return ToolResult.error("path is required.").toJson(); //$NON-NLS-1$
        }
        Path p = Paths.get(path.trim());
        if (!Files.isRegularFile(p))
        {
            return ToolResult.error("No such feature file: " + path).toJson(); //$NON-NLS-1$
        }

        final Map<String, Object> parsed;
        final String raw;
        try
        {
            parsed = VanessaFeatureScanner.parse(p);
            raw = String.join("\n", Files.readAllLines(p, StandardCharsets.UTF_8)); //$NON-NLS-1$
        }
        catch (IOException e)
        {
            return ToolResult.error("Failed to read feature file '" + path + "': " + e.getMessage()).toJson(); //$NON-NLS-1$
        }

        return ToolResult.success()
            .put("path", p.toString()) //$NON-NLS-1$
            .put("feature", parsed.get("feature")) //$NON-NLS-1$
            .put("tags", parsed.get("tags")) //$NON-NLS-1$
            .put("scenarios", parsed.get("scenarios")) //$NON-NLS-1$
            .put("raw", raw) //$NON-NLS-1$
            .toJson();
    }
}
