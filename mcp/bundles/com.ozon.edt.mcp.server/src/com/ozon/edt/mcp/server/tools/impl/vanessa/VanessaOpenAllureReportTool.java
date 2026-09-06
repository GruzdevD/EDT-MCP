/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 *
 * edt-mcp-vanessa: open a Vanessa BDD run's Allure report in EDT.
 */

package com.ozon.edt.mcp.server.tools.impl.vanessa;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.eclipse.swt.program.Program;

import com.ozon.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ozon.edt.mcp.server.protocol.ToolResult;
import com.ozon.edt.mcp.server.tools.IMcpTool;
import com.ozon.edt.mcp.server.ui.AllureHttpServer;
import com.ozon.edt.mcp.server.ui.AllureReportView;

/**
 * Generates a static Allure report from the raw results of a Vanessa Automation
 * BDD run and opens it — by default in the in-EDT {@link AllureReportView}
 * (SWT {@code Browser} over the local {@link AllureHttpServer}), or in the OS
 * browser when {@code detached} is set. The run is addressed by {@code launchId}
 * (reusing the handled out dir) or by an explicit {@code outDir}.
 *
 * <p>Generation requires the {@code allure} commandline: {@code resolveAllureBin}
 * looks it up via the {@code allureBin} parameter, the project {@code env.sh}
 * {@code ALLURE_BIN}, the local {@code ~/.1c-tools/allure} install, then
 * {@code PATH}. When {@code generate=false} the report is expected to already
 * exist next to the results.
 */
public class VanessaOpenAllureReportTool implements IMcpTool
{
    public static final String NAME = "vanessa_open_allure_report"; //$NON-NLS-1$

    private static final String KEY_LAUNCH_ID = "launchId"; //$NON-NLS-1$
    private static final String KEY_OUT_DIR = "outDir"; //$NON-NLS-1$
    private static final String KEY_DETACHED = "detached"; //$NON-NLS-1$
    private static final String KEY_ALLURE_BIN = "allureBin"; //$NON-NLS-1$
    private static final String KEY_GENERATE = "generate"; //$NON-NLS-1$

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Generate and open the Allure report of a Vanessa Automation BDD run. Address the run by " //$NON-NLS-1$
            + "launchId (from vanessa_run_feature) or absolute outDir. Generates the static report from the raw " //$NON-NLS-1$
            + "Allure results via the allure commandline, serves it over loopback and opens it in the in-EDT " //$NON-NLS-1$
            + "Allure Report view (or the OS browser when detached). Parameters and examples: " //$NON-NLS-1$
            + "get_tool_guide('vanessa_open_allure_report')."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .integerProperty(KEY_LAUNCH_ID,
                "A launchId from vanessa_run_feature whose out dir is used. Either this or outDir is required.") //$NON-NLS-1$
            .stringProperty(KEY_OUT_DIR,
                "Absolute out dir of the run. Either this or launchId is required.") //$NON-NLS-1$
            .booleanProperty(KEY_DETACHED,
                "Open in the OS browser instead of the in-EDT view. Default false.") //$NON-NLS-1$
            .stringProperty(KEY_ALLURE_BIN,
                "Override the allure commandline path (otherwise env.sh ALLURE_BIN, ~/.1c-tools/allure, PATH).") //$NON-NLS-1$
            .booleanProperty(KEY_GENERATE,
                "Generate the report before opening. Default true; false expects an existing allure-report dir.") //$NON-NLS-1$
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
        String outDir = nonEmpty(params.get(KEY_OUT_DIR));
        if (outDir == null)
        {
            Long id = VanessaLaunchRunner.parseLaunchId(params.get(KEY_LAUNCH_ID));
            if (id == null)
            {
                return ToolResult.error("Either outDir or a numeric launchId is required.").toJson(); //$NON-NLS-1$
            }
            VanessaLaunchRunner.RunHandle handle = VanessaLaunchRunner.INSTANCE.get(id);
            if (handle == null)
            {
                return ToolResult.error("Unknown launchId " + id + ".").toJson(); //$NON-NLS-1$
            }
            outDir = handle.outDir;
        }

        Path root = Paths.get(outDir);
        if (!Files.isDirectory(root))
        {
            return ToolResult.error("No such out dir: " + outDir).toJson(); //$NON-NLS-1$
        }

        Path resultsDir = AllureReportService.findResultsDir(root);
        if (resultsDir == null)
        {
            return ToolResult.error("No Allure results in '" + outDir //$NON-NLS-1$
                + "' (expected *-result.json under '" + AllureReportService.ALLURE_RESULTS_DIR //$NON-NLS-1$
                + "' or in the out dir itself). Enable Allure output для the run (ДелатьОтчетВФорматеАллюр).") //$NON-NLS-1$
                .toJson();
        }

        boolean generate = !"false".equalsIgnoreCase(params.get(KEY_GENERATE)); //$NON-NLS-1$
        boolean detached = Boolean.parseBoolean(params.get(KEY_DETACHED));

        final Path reportDir;
        if (generate)
        {
            String allureBin = resolveAllureBin(root, params.get(KEY_ALLURE_BIN));
            if (allureBin == null)
            {
                return ToolResult.error("Allure CLI not found. Install it (e.g. unzip into ~/.1c-tools/allure/), " //$NON-NLS-1$
                    + "set ALLURE_BIN in the project env.sh, or pass allureBin.").toJson(); //$NON-NLS-1$
            }
            try
            {
                reportDir = AllureReportService.generate(
                    System.getProperty("user.home"), null, Paths.get(allureBin), resultsDir); //$NON-NLS-1$
            }
            catch (Exception e)
            {
                return ToolResult.error("Failed to generate the Allure report: " + e.getMessage()).toJson(); //$NON-NLS-1$
            }
        }
        else
        {
            reportDir = resultsDir.getParent().resolve(AllureReportService.REPORT_DIR);
            if (!Files.isRegularFile(reportDir.resolve(AllureReportService.INDEX)))
            {
                return ToolResult.error("No existing report at " + reportDir //$NON-NLS-1$
                    + " (generate=false but " + AllureReportService.INDEX + " is missing).") //$NON-NLS-1$ //$NON-NLS-2$
                    .toJson();
            }
        }

        final String url;
        if (detached)
        {
            try
            {
                url = AllureHttpServer.getInstance().start(reportDir);
            }
            catch (Exception e)
            {
                return ToolResult.error("Failed to start the Allure report server: " + e.getMessage()).toJson(); //$NON-NLS-1$
            }
            if (url != null)
            {
                Program.launch(url);
            }
        }
        else
        {
            url = AllureReportView.open(reportDir);
            if (url == null)
            {
                return ToolResult.error("No EDT UI available to show the view - retry with detached=true " //$NON-NLS-1$
                    + "to open the report in the OS browser.").toJson(); //$NON-NLS-1$
            }
        }

        return ToolResult.success()
            .put(KEY_OUT_DIR, root.toAbsolutePath().toString()) //$NON-NLS-1$
            .put("resultsDir", resultsDir.toAbsolutePath().toString()) //$NON-NLS-1$
            .put("reportDir", reportDir.toAbsolutePath().toString()) //$NON-NLS-1$
            .put("url", url) //$NON-NLS-1$
            .put("port", AllureHttpServer.getInstance().port()) //$NON-NLS-1$
            .put(KEY_DETACHED, detached) //$NON-NLS-1$
            .toJson();
    }

    /** The allure binary for a result dir: explicit override → env.sh → install → PATH. */
    private static String resolveAllureBin(Path root, String explicit)
    {
        Path bin = AllureReportService.resolveAllureBin(deriveProject(root), explicit);
        return bin == null ? null : bin.toAbsolutePath().toString();
    }

    /**
     * Best-effort project key from an out dir path: the path segment right after
     * a {@code .../vanessa/out/<proj>} segment, or {@code null}.
     */
    static String deriveProject(Path outDir)
    {
        List<String> segs = new ArrayList<>();
        outDir.toAbsolutePath().normalize().iterator().forEachRemaining(s -> segs.add(s.toString()));
        for (int i = 0; i + 1 < segs.size(); i++)
        {
            if ("out".equals(segs.get(i))) //$NON-NLS-1$
            {
                return segs.get(i + 1);
            }
        }
        return null;
    }

    private static String nonEmpty(String value)
    {
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }
}
