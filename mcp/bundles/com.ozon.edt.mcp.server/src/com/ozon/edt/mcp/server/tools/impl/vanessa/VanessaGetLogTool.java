/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 *
 * edt-mcp-vanessa: page through the Vanessa BDD run log.
 */

package com.ozon.edt.mcp.server.tools.impl.vanessa;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

import com.ozon.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ozon.edt.mcp.server.protocol.ToolResult;
import com.ozon.edt.mcp.server.tools.IMcpTool;

/**
 * Reads the per-run {@code BDD.log} Vanessa writes into the out dir. The log can
 * be addressed by {@code launchId} (reusing the handle's out dir) or by an
 * explicit absolute {@code logPath}; large logs are paged with
 * {@code offsetLines}/{@code limit}. Pure file work on the run artifacts.
 */
public class VanessaGetLogTool implements IMcpTool
{
    public static final String NAME = "vanessa_get_log"; //$NON-NLS-1$

    private static final String KEY_LAUNCH_ID = "launchId"; //$NON-NLS-1$
    private static final String KEY_LOG_PATH = "logPath"; //$NON-NLS-1$
    private static final String KEY_OFFSET = "offsetLines"; //$NON-NLS-1$
    private static final String KEY_LIMIT = "limit"; //$NON-NLS-1$

    /** Default and cap for the number of log lines returned in one page. */
    private static final int DEFAULT_LIMIT = 200; //$NON-NLS-1$
    private static final int MAX_LIMIT = 2000; //$NON-NLS-1$

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Read lines from a Vanessa Automation run log (BDD.log). Address it by launchId (reuses the " //$NON-NLS-1$
            + "run's out dir) or by absolute logPath; page big logs with offsetLines/limit. Returns the lines, " //$NON-NLS-1$
            + "offsets and a 'more' flag. Parameters and examples: get_tool_guide('vanessa_get_log')."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .integerProperty(KEY_LAUNCH_ID,
                "A launchId from vanessa_run_feature whose recorded BDD.log is read. Either this or logPath is required.") //$NON-NLS-1$
            .stringProperty(KEY_LOG_PATH,
                "Absolute path to a BDD.log. Either this or launchId is required.") //$NON-NLS-1$
            .integerProperty(KEY_OFFSET,
                "Zero-based line offset to start from (default 0).") //$NON-NLS-1$
            .integerProperty(KEY_LIMIT,
                "Max lines to return (default 200, capped at 2000).") //$NON-NLS-1$
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
        String logPath = params.get(KEY_LOG_PATH);
        if (logPath != null && !logPath.trim().isEmpty())
        {
            logPath = logPath.trim();
        }
        else
        {
            Long id = VanessaLaunchRunner.parseLaunchId(params.get(KEY_LAUNCH_ID));
            if (id == null)
            {
                return ToolResult.error("Either logPath or a numeric launchId is required.").toJson(); //$NON-NLS-1$
            }
            VanessaLaunchRunner.RunHandle handle = VanessaLaunchRunner.INSTANCE.get(id);
            if (handle == null)
            {
                return ToolResult.error("Unknown launchId " + id + ".").toJson(); //$NON-NLS-1$
            }
            logPath = Paths.get(handle.outDir, VanessaLaunchRunner.LOG_FILE).toString();
        }

        if (!Files.isRegularFile(Paths.get(logPath)))
        {
            return ToolResult.error("No log file at " + logPath).toJson(); //$NON-NLS-1$
        }

        int offset = nonNeg(params.get(KEY_OFFSET), 0);
        int limit = nonNeg(params.get(KEY_LIMIT), DEFAULT_LIMIT);
        if (limit <= 0)
        {
            limit = DEFAULT_LIMIT;
        }
        if (limit > MAX_LIMIT)
        {
            limit = MAX_LIMIT;
        }

        final List<String> all;
        try
        {
            all = Files.readAllLines(Paths.get(logPath), StandardCharsets.UTF_8);
        }
        catch (IOException e)
        {
            return ToolResult.error("Failed to read log '" + logPath + "': " + e.getMessage()).toJson(); //$NON-NLS-1$
        }

        int total = all.size();
        int start = Math.min(offset, total);
        int end = Math.min(start + limit, total);
        List<String> window = all.subList(start, end);
        boolean more = end < total;

        return ToolResult.success()
            .put("path", logPath) //$NON-NLS-1$
            .put("totalLines", total) //$NON-NLS-1$
            .put("offset", start) //$NON-NLS-1$
            .put("returned", window.size()) //$NON-NLS-1$
            .put("more", more) //$NON-NLS-1$
            .put("lines", window) //$NON-NLS-1$
            .toJson();
    }

    private static int nonNeg(String raw, int def)
    {
        if (raw == null || raw.trim().isEmpty())
        {
            return def;
        }
        try
        {
            int v = Integer.parseInt(raw.trim());
            return Math.max(0, v);
        }
        catch (NumberFormatException e)
        {
            return def;
        }
    }
}
