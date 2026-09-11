/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 *
 * edt-mcp-vanessa: enumerate the artifacts a Vanessa BDD run produced.
 */

package com.ditrix.edt.mcp.server.tools.impl.vanessa;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.IMcpTool;

/**
 * Lists the files a Vanessa run wrote into its out dir — junit report, Allure
 * artifacts, screenshots and the log — each with a size and a coarse {@code kind}
 * classification. The dir is addressed by {@code launchId} (reusing the handled
 * out dir) or by an explicit absolute {@code outDir}. Pure file work on the run
 * artifacts.
 */
public class VanessaGetArtifactsTool implements IMcpTool
{
    public static final String NAME = "vanessa_get_artifacts"; //$NON-NLS-1$

    private static final String KEY_LAUNCH_ID = "launchId"; //$NON-NLS-1$
    private static final String KEY_OUT_DIR = "outDir"; //$NON-NLS-1$

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "List the artifacts a Vanessa Automation run produced in its out dir: junit report, Allure " //$NON-NLS-1$
            + "files, screenshots and the run log, each with a size and a coarse kind. Address the dir by " //$NON-NLS-1$
            + "launchId or absolute outDir. Parameters and examples: get_tool_guide('vanessa_get_artifacts')."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .integerProperty(KEY_LAUNCH_ID,
                "A launchId from vanessa_run_feature whose out dir is scanned. Either this or outDir is required.") //$NON-NLS-1$
            .stringProperty(KEY_OUT_DIR,
                "Absolute out dir to scan. Either this or launchId is required.") //$NON-NLS-1$
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
            .stringProperty("outDir", "Absolute out dir that was scanned") //$NON-NLS-1$ //$NON-NLS-2$
            .integerProperty("count", "Number of artifacts listed") //$NON-NLS-1$ //$NON-NLS-2$
            .objectArrayProperty("artifacts", "The run artifacts (path, size, kind)") //$NON-NLS-1$ //$NON-NLS-2$
            .build();
    }

    @Override
    public String execute(Map<String, String> params)
    {
        String outDir = params.get(KEY_OUT_DIR);
        if (outDir != null && !outDir.trim().isEmpty())
        {
            outDir = outDir.trim();
        }
        else
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

        List<Map<String, Object>> artifacts = new ArrayList<>();
        try (Stream<Path> s = Files.walk(root))
        {
            s.filter(Files::isRegularFile)
                .sorted()
                .forEach(p -> artifacts.add(describe(p)));
        }
        catch (IOException e)
        {
            return ToolResult.error("Failed to scan out dir '" + outDir + "': " + e.getMessage()).toJson(); //$NON-NLS-1$
        }

        return ToolResult.success()
            .put("outDir", root.toString()) //$NON-NLS-1$
            .put("count", artifacts.size()) //$NON-NLS-1$
            .put("artifacts", artifacts) //$NON-NLS-1$
            .toJson();
    }

    private static Map<String, Object> describe(Path p)
    {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("path", p.toString()); //$NON-NLS-1$
        try
        {
            item.put("size", Files.size(p)); //$NON-NLS-1$
        }
        catch (IOException e)
        {
            item.put("size", 0L); //$NON-NLS-1$
        }
        item.put("kind", kind(p));
        return item;
    }

    /** Coarse classification of a run artifact by its path. */
    static String kind(Path p)
    {
        String lower = p.toString().toLowerCase();
        String file = p.getFileName().toString().toLowerCase();
        if (lower.contains("allure")) //$NON-NLS-1$
        {
            return "allure"; //$NON-NLS-1$
        }
        if (file.endsWith(".png") || file.endsWith(".jpg") //$NON-NLS-1$ //$NON-NLS-2$
            || file.endsWith(".jpeg") || file.endsWith(".gif")) //$NON-NLS-1$ //$NON-NLS-2$
        {
            return "screenshot"; //$NON-NLS-1$
        }
        if (file.equals("junit.xml")) //$NON-NLS-1$
        {
            return "junit"; //$NON-NLS-1$
        }
        if (lower.contains("bddstatus") || lower.contains(".log")) //$NON-NLS-1$ //$NON-NLS-2$
        {
            return "log"; //$NON-NLS-1$
        }
        if (file.endsWith(".csv") || file.endsWith(".json") || file.endsWith(".xml") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            || file.endsWith(".txt") || file.endsWith(".html")) //$NON-NLS-1$ //$NON-NLS-2$
        {
            return "data"; //$NON-NLS-1$
        }
        return "other"; //$NON-NLS-1$
    }
}
