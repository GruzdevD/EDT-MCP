/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 *
 * edt-mcp-vanessa: readiness report for the provisioned Vanessa Automation runtime.
 */

package com.ditrix.edt.mcp.server.tools.impl.vanessa;

import java.nio.file.Path;
import java.util.Map;

import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.IMcpTool;

/**
 * Read-only {@code vanessa_doctor}: reports how ready a project's Vanessa Automation
 * environment is, without downloading or changing anything. It mirrors the readiness
 * model of {@link VanessaBootstrap#doctor} (layout, env.sh, VAParams.json, the epf
 * runtime, Allure CLI) as a Markdown checklist plus the next action to take.
 */
public class VanessaDoctorTool implements IMcpTool
{
    public static final String NAME = "vanessa_doctor"; //$NON-NLS-1$

    private static final String KEY_PROJECT = "project"; //$NON-NLS-1$

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Read-only readiness report for a project's Vanessa Automation environment (layout, " //$NON-NLS-1$
            + "env.sh, VAParams.json, the vanessa-automation.epf runtime, Allure CLI). Does not " //$NON-NLS-1$
            + "download or change anything. Use vanessa_setup to provision. Parameters and examples: " //$NON-NLS-1$
            + "get_tool_guide('vanessa_doctor')."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty(KEY_PROJECT,
                "Project key whose VA environment to inspect, e.g. 'afm'.", //$NON-NLS-1$
                true)
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

        Path projectRoot = VanessaProjectConfig.projectRoot(project);
        VanessaBootstrap.Doctor doctor = VanessaBootstrap.doctor(projectRoot, project);
        return render(project, doctor);
    }

    /** Renders the readiness checklist as Markdown (never fails). */
    static String render(String project, VanessaBootstrap.Doctor doctor)
    {
        StringBuilder s = new StringBuilder();
        s.append("# Vanessa doctor: ").append(project).append('\n');
        if (doctor.ready())
        {
            s.append("\n**Ready:** the VA runtime is installed and this project can launch a "); //$NON-NLS-1$
            s.append("BDD run (vanessa_run_feature).\n");
        }
        else
        {
            s.append("\n**Not ready.** Provision with `vanessa_setup` (project=\""); //$NON-NLS-1$
            s.append(project).append("\"), then run this again.\n");
        }

        s.append("\n| Prerequisite | State |\n|---|---|\n");
        s.append(row("Layout `.vanessa/{features,out}`", doctor.layout));
        s.append(row("`env.sh` present", doctor.envSh));
        s.append(row("VAParams.json present", doctor.vaparams));
        s.append(row("Vanessa runtime `.epf` installed", doctor.epf));
        s.append(row("Allure CLI (report, optional)", doctor.allure));
        s.append(row("vrunner CLI (legacy, not required)", doctor.vrunner));

        s.append("\n## Next steps\n");
        if (!doctor.layout || !doctor.envSh || !doctor.vaparams)
        {
            s.append("- Run `vanessa_setup` to create `.vanessa/` (env.sh, VAParams.json, features/, "); //$NON-NLS-1$
            s.append("out/) and download the VA runtime.\n");
        }
        else if (!doctor.epf)
        {
            s.append("- The VA runtime is not installed yet. `vanessa_setup` downloads it lazily, and "); //$NON-NLS-1$
            s.append("the first `vanessa_run_feature` triggers that download.\n");
        }
        if (!doctor.allure)
        {
            s.append("- Allure CLI is optional (only `vanessa_open_allure_report`). `vanessa_setup` "); //$NON-NLS-1$
            s.append("with installAllure=true, or set ALLURE_BIN in env.sh.\n");
        }
        if (doctor.ready())
        {
            s.append("- Environment ready: launch `vanessa_run_feature`, inspect reports with "); //$NON-NLS-1$
            s.append("`vanessa_get_test_report` / `vanessa_get_execution_status`.\n");
        }
        return s.toString();
    }

    private static String row(String label, boolean ok)
    {
        return "| " + label + " | " + (ok ? ":white_check_mark: ready" : ":x: missing") + " |\n"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }
}
