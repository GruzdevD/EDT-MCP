/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 *
 * edt-mcp-vanessa: parse a Vanessa JUnit XML report.
 */

package com.ditrix.edt.mcp.server.tools.impl.vanessa;

import java.util.Map;

import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.IMcpTool;

/**
 * Returns a parsed, client-friendly summary of a Vanessa Automation junit.xml
 * report. The report path may come directly as {@code junitReportPath}, or from a
 * previously launched run referenced by {@code launchId}.
 */
public class VanessaGetTestReportTool implements IMcpTool
{
    public static final String NAME = "vanessa_get_test_report"; //$NON-NLS-1$

    private static final String KEY_JUNIT_REPORT = "junitReportPath"; //$NON-NLS-1$
    private static final String KEY_LAUNCH_ID = "launchId"; //$NON-NLS-1$
    private static final String KEY_DETAIL = "detail"; //$NON-NLS-1$

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Parse a Vanessa Automation junit.xml report into a structured summary: per-suite counts, " //$NON-NLS-1$
            + "per-test status (passed/failed/error/skipped) and an overall verdict. Pass junitReportPath " //$NON-NLS-1$
            + "directly, or launchId to reuse the path recorded by vanessa_run_feature. " //$NON-NLS-1$
            + "Parameters and examples: get_tool_guide('vanessa_get_test_report')."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty(KEY_JUNIT_REPORT,
                "Absolute path to the junit.xml report. Either this or launchId is required.") //$NON-NLS-1$
            .integerProperty(KEY_LAUNCH_ID,
                "A launchId from vanessa_run_feature whose recorded junitReportPath is used.") //$NON-NLS-1$
            .enumProperty(KEY_DETAIL,
                "How much per-test detail to include: 'summary' (counts + verdict only), 'tests' (default, " //$NON-NLS-1$
                    + "per-test status/message), or 'steps' (tests plus attachments and the full failure text).", //$NON-NLS-1$
                "summary", "tests", "steps") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            .build();
    }

    @Override
    public ResponseType getResponseType()
    {
        return ResponseType.JSON;
    }

    @Override
    public String getOutputSchema()
    {
        return JsonSchemaBuilder.object()
            .booleanProperty("success", "Whether the call succeeded", true) //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("report", "The rendered Markdown JUnit report, or an error note") //$NON-NLS-1$ //$NON-NLS-2$
            .build();
    }

    @Override
    public String execute(Map<String, String> params)
    {
        String junitPath = params.get(KEY_JUNIT_REPORT);
        if (junitPath == null || junitPath.trim().isEmpty())
        {
            String rawId = params.get(KEY_LAUNCH_ID);
            if (rawId == null || rawId.trim().isEmpty())
            {
                return ToolResult.error("Either junitReportPath or launchId is required.").toJson(); //$NON-NLS-1$
            }
            // launchId arrives as Double.toString() of the JSON number ("1" -> "1.0"), so parse
            // robustly the same way the status tool does, via the shared helper on the runner.
            Long id = VanessaLaunchRunner.parseLaunchId(rawId);
            if (id == null)
            {
                return ToolResult.error("Invalid launchId: " + rawId).toJson(); //$NON-NLS-1$
            }
            VanessaLaunchRunner.RunHandle handle = VanessaLaunchRunner.INSTANCE.get(id);
            if (handle == null)
            {
                return ToolResult.error("Unknown launchId " + id + ".").toJson(); //$NON-NLS-1$
            }
            junitPath = handle.junitReportPath;
            if (junitPath == null || junitPath.trim().isEmpty())
            {
                return ToolResult.error("No junitReportPath was recorded for launchId " + id + ".").toJson(); //$NON-NLS-1$
            }
        }

        try
        {
            JUnitReportParser.Detail detail = resolveDetail(params.get(KEY_DETAIL));
            Map<String, Object> report = JUnitReportParser.parse(junitPath.trim(), detail);
            return ToolResult.success()
                .put("report", report) //$NON-NLS-1$
                .toJson();
        }
        catch (Exception e)
        {
            return ToolResult.error("Failed to parse JUnit report '" + junitPath + "': " + e.getMessage()).toJson(); //$NON-NLS-1$
        }
    }

    /** Maps the {@code detail} parameter onto {@link JUnitReportParser.Detail} (default {@code tests}). */
    private static JUnitReportParser.Detail resolveDetail(String raw)
    {
        if (raw != null)
        {
            String v = raw.trim().toLowerCase();
            if ("summary".equals(v)) //$NON-NLS-1$
            {
                return JUnitReportParser.Detail.SUMMARY;
            }
            if ("steps".equals(v)) //$NON-NLS-1$
            {
                return JUnitReportParser.Detail.STEPS;
            }
        }
        return JUnitReportParser.Detail.TESTS;
    }
}
