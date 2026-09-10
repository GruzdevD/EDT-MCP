/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 *
 * edt-mcp-vanessa: explicit turnkey provisioning of the Vanessa Automation runtime.
 */

package com.ditrix.edt.mcp.server.tools.impl.vanessa;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.utils.BackgroundJobs;

/**
 * Explicit {@code vanessa_setup}: provisions (idempotently) the project's
 * {@code .vanessa/} layout from the bundled templates and downloads the VA runtime
 * (and, optionally, the Allure CLI) as a {@link BackgroundJobs} job pollable with
 * {@code get_job_status}. This is the harness-facing entry point for "install everything".
 */
public class VanessaSetupTool implements IMcpTool
{
    public static final String NAME = "vanessa_setup"; //$NON-NLS-1$

    private static final String KEY_PROJECT = "project"; //$NON-NLS-1$
    private static final String KEY_INSTALL_ALLURE = "installAllure"; //$NON-NLS-1$

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Turnkey provisioning of a project's Vanessa Automation environment: creates " //$NON-NLS-1$
            + ".vanessa/ (env.sh, VAParams.json, features/, out/) and downloads the " //$NON-NLS-1$
            + "vanessa-automation.epf runtime (plus the optional Allure CLI) as a background job " //$NON-NLS-1$
            + "pollable with get_job_status. Idempotent: re-running never overwrites an edited " //$NON-NLS-1$
            + "env.sh. Use vanessa_doctor to see what is still missing. Parameters and examples: " //$NON-NLS-1$
            + "get_tool_guide('vanessa_setup')."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty(KEY_PROJECT,
                "Project key whose VA environment to provision, e.g. 'afm'.", //$NON-NLS-1$
                true)
            .booleanProperty(KEY_INSTALL_ALLURE,
                "Optional: also download the Allure CLI into ~/.1c-tools/allure so " //$NON-NLS-1$
                + "vanessa_open_allure_report works. Default false.")
            .build();
    }

    @Override
    public ResponseType getResponseType()
    {
        return ResponseType.MARKDOWN;
    }

    @Override
    public String execute(Map<String, String> params)
    {
        String project = params.get(KEY_PROJECT);
        if (project == null || project.trim().isEmpty())
        {
            return ToolResult.error("project is required.").toJson(); //$NON-NLS-1$
        }
        project = project.trim();
        boolean installAllure = Boolean.parseBoolean(params.get(KEY_INSTALL_ALLURE));

        Path projectRoot = VanessaProjectConfig.projectRoot(project);

        // Provision the (light) layout synchronously so env.sh/VAParams.json exist even before
        // the heavy runtime download finishes.
        String setupError = provisionLayout(project, projectRoot);
        if (setupError != null)
        {
            return ToolResult.error(setupError).toJson();
        }

        // Already ready (runtime installed) -> just report, no job needed.
        if (VanessaBootstrap.doctor(projectRoot, project).ready() && !installAllure)
        {
            return "# Vanessa setup: " + project + "\n\nEnvironment is ready.\n\n" //$NON-NLS-1$ //$NON-NLS-2$
                + VanessaDoctorTool.render(project, VanessaBootstrap.doctor(projectRoot, project));
        }

        if (VanessaBootstrap.isEpfDownloading(project))
        {
            String jobId = VanessaBootstrap.epfDownloadJobId(project);
            return pendingMessage(project, jobId, "already in progress");
        }

        BackgroundJobs.JobSnapshot job = VanessaBootstrap.scheduleEpfDownload(
            NAME, projectRoot, project, installAllure);
        if (job == null)
        {
            // Lost the race to another caller; its job id may already be recorded.
            String jobId = VanessaBootstrap.epfDownloadJobId(project);
            return pendingMessage(project, jobId, "already in progress");
        }
        return pendingMessage(project, job.getId(), "started");
    }

    private static String pendingMessage(String project, String jobId, String how)
    {
        StringBuilder s = new StringBuilder();
        s.append("# Vanessa setup: ").append(project).append('\n');
        s.append("\n**Provisioning ").append(how).append(".** The VA runtime download runs in "); //$NON-NLS-1$
        s.append("background job ");
        if (jobId == null)
        {
            s.append("(id being registered)");
        }
        else
        {
            s.append('`').append(jobId).append('`');
        }
        s.append(".\n\nPoll it with `get_job_status` using `jobId=\"").append(jobId).append("\"`, "); //$NON-NLS-1$
        s.append("then confirm with `vanessa_doctor`. Do not repeat the original "); //$NON-NLS-1$
        s.append("vanessa_setup call to address this run.\n");
        return s.toString();
    }

    /**
     * Provisions the idempotent layout, or returns an error message on failure.
     *
     * @param project the project key
     * @param projectRoot the resolved project root (may be null on a non-EDT key)
     * @return {@code null} on success, else an error message
     */
    private static String provisionLayout(String project, Path projectRoot)
    {
        if (projectRoot == null)
        {
            // No EDT project root: still provision to the legacy home layout so the tools keep a
            // consistent place to read from.
            projectRoot = Path.of(System.getProperty("user.home"), ".1c-tools/vanessa/projects", project); //$NON-NLS-1$ //$NON-NLS-2$
            try
            {
                Files.createDirectories(projectRoot);
            }
            catch (java.io.IOException e)
            {
                return "Failed to create " + projectRoot + ": " + e.getMessage(); //$NON-NLS-1$
            }
        }
        String envTemplate = VanessaBootstrap.template("env.sh.template"); //$NON-NLS-1$
        String vaparamsTemplate = VanessaBootstrap.template("VAParams.json.template"); //$NON-NLS-1$
        try
        {
            VanessaBootstrap.provisionLayout(projectRoot, project, envTemplate, vaparamsTemplate);
            // When provisioning into an EDT project (not the legacy home fallback), mark
            // .vanessa as a derived resource so EDT does not sweep it into project indexing.
            if (projectRoot != null)
            {
                VanessaBootstrap.markVanessaDerived(project);
            }
            return null;
        }
        catch (java.io.IOException e)
        {
            return "Failed to provision .vanessa for '" + project + "': " + e.getMessage(); //$NON-NLS-1$
        }
    }
}
