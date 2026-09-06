/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 *
 * edt-mcp-vanessa: shared result of a Vanessa Automation BDD launch.
 */

package com.ozon.edt.mcp.server.tools.impl.vanessa;

/**
 * What a Vanessa Automation launch attempt produced, once the shared launch
 * path ({@link VanessaRunFeatureTool#launch}) has generated the run artifacts,
 * launched VA_Runner on the EDT launch configuration and registered a
 * {@link VanessaLaunchRunner.RunHandle}. Both {@code vanessa_run_feature} and
 * {@code vanessa_run_by_tags} build their responses from this so the launch
 * pipeline is never duplicated.
 */
public final class VanessaLaunchOutcome
{
    /** True when the launch was accepted (a run is in flight). */
    public final boolean success;
    /**
     * The raw {@code LaunchTool} JSON response. On failure it already carries
     * {@code success:false} (and may be returned to the client verbatim); on
     * success it is informational.
     */
    public final String rawResponse;
    /** Launch handle id ({@code 0} when the launch was rejected). */
    public final long id;
    public final String project;
    public final String feature;
    public final String configuration;
    /** Absolute out directory where VA writes logs/junit/allure. */
    public final String outDir;
    /** Absolute junit.xml path expected for this run. */
    public final String junitReportPath;

    VanessaLaunchOutcome(boolean success, String rawResponse, long id, String project,
        String feature, String configuration, String outDir, String junitReportPath)
    {
        this.success = success;
        this.rawResponse = rawResponse;
        this.id = id;
        this.project = project;
        this.feature = feature;
        this.configuration = configuration;
        this.outDir = outDir;
        this.junitReportPath = junitReportPath;
    }
}
