/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.PlatformUI;

import com._1c.g5.v8.dt.core.platform.IExtensionProject;
import com._1c.g5.v8.dt.core.platform.IV8Project;
import com._1c.g5.v8.dt.metadata.mdclass.CommonModule;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.McpKeys;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.utils.BslModuleUtils;
import com.ditrix.edt.mcp.server.utils.ExtensionOriginUtils;
import com.ditrix.edt.mcp.server.utils.ListYaxunitTestsSupport;
import com.ditrix.edt.mcp.server.utils.ListYaxunitTestsSupport.Suite;
import com.ditrix.edt.mcp.server.utils.Pagination;
import com.ditrix.edt.mcp.server.utils.ProjectContext;

/**
 * Tool to enumerate YAXUnit test suites and the tests within each suite for a
 * 1C:Enterprise project.
 *
 * <p>Projects in this org run YAXUnit in the {@code Ют} programmatic style: a test
 * suite is a common module (usually in a {@code *.YAXUNIT} extension) whose source
 * registers test methods through the fluent builder
 * {@code ЮТТесты...ДобавитьТестовыйНабор("Имя").ДобавитьТест("ТестМетод")...}. The
 * tool walks the common modules of the base configuration and of every extension
 * project that derives from it, keeps the ones that register a test set, and returns a
 * JSON envelope {@code {project, count, testCount, suites:[{moduleName, modulePath,
 * moduleType, parentName, tests:[{name}]}]}}. A user or client then feeds the
 * {@code modules} (the {@code moduleName} of a whole suite) or {@code tests}
 * ({@code Module.Method} names) lists back into {@code run_yaxunit_tests} to run a
 * subset at the granularity they selected.</p>
 */
public class ListYaxunitTestsTool implements IMcpTool
{
    public static final String NAME = "list_yaxunit_tests"; //$NON-NLS-1$

    /** Default {@code limit} when the caller omits it. */
    private static final int DEFAULT_LIMIT = 200;

    /** Suffix of the common-object module file name, as {@code list_modules} reports it. */
    private static final String COMMON_MODULE_FOLDER = "CommonModules"; //$NON-NLS-1$
    private static final String MODULE_FILE = "Module.bsl"; //$NON-NLS-1$
    private static final String MODULE_TYPE = "Module"; //$NON-NLS-1$
    private static final String PARENT_TYPE = "CommonModule"; //$NON-NLS-1$

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Enumerate YAXUnit test suites (common modules that register a test set via " //$NON-NLS-1$
            + "'ЮТТесты...ДобавитьТестовыйНабор(...).ДобавитьТест(...)') and the tests within each " //$NON-NLS-1$
            + "suite for a project. Returns JSON: {project, count, testCount, suites:[{moduleName, " //$NON-NLS-1$
            + "modulePath, moduleType, parentName, tests:[{name}]}]}. Feed a whole suite's moduleName " //$NON-NLS-1$
            + "into `modules`, or individual `Module.Method` names into `tests`, of " //$NON-NLS-1$
            + "`run_yaxunit_tests` to run at that granularity. Parameters and " //$NON-NLS-1$
            + "examples: get_tool_guide('list_yaxunit_tests')."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty(McpKeys.PROJECT_NAME,
                "EDT project name whose YAXUnit test suites to enumerate (required).") //$NON-NLS-1$
            .integerProperty(McpKeys.LIMIT,
                "Maximum number of suites to return (default 200, max 1000).") //$NON-NLS-1$
            .build();
    }

    @Override
    public ResponseType getResponseType()
    {
        return ResponseType.MARKDOWN;
    }

    @Override
    public boolean connectsToInfobase()
    {
        return false;
    }

    @Override
    public String getResultFileName(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, McpKeys.PROJECT_NAME);
        if (projectName != null && !projectName.isEmpty())
        {
            return "yaxunit-tests-" + projectName.toLowerCase() + ".json"; //$NON-NLS-1$ //$NON-NLS-2$
        }
        return "yaxunit-tests.json"; //$NON-NLS-1$
    }

    @Override
    public String execute(Map<String, String> params)
    {
        String err = JsonUtils.requireArgument(params, McpKeys.PROJECT_NAME);
        if (err != null)
        {
            return err;
        }
        String projectName = JsonUtils.extractStringArgument(params, McpKeys.PROJECT_NAME);
        int limit = Pagination.clampLimit(JsonUtils.extractIntArgument(params, McpKeys.LIMIT,
            DEFAULT_LIMIT), Pagination.MAX_LIMIT);

        AtomicReference<String> resultRef = new AtomicReference<>();
        Display display = PlatformUI.getWorkbench().getDisplay();
        display.syncExec(() ->
        {
            try
            {
                resultRef.set(listYaxunitTestsInternal(projectName, limit));
            }
            catch (Exception e)
            {
                Activator.logError("Error listing YAXUnit tests", e); //$NON-NLS-1$
                resultRef.set(ToolResult.error(e.getMessage()).toJson());
            }
        });
        return resultRef.get();
    }

    private String listYaxunitTestsInternal(String projectName, int limit)
    {
        ProjectContext ctx = ProjectContext.of(projectName);
        if (!ctx.exists())
        {
            return ToolResult.error(ProjectContext.notFoundMessage(projectName)).toJson();
        }
        IProject baseProject = ctx.project();

        ProjectContext.ConfigurationResult resolved = ctx.resolveConfiguration();
        if (!resolved.ok())
        {
            return resolved.errorJson();
        }
        Configuration baseConfig = resolved.configuration();

        List<Suite> suites = new ArrayList<>();
        // Track suite names already collected so a module registered in both the base and
        // an extension is listed once. Names that resolve to a NON-suite never consume a
        // slot, so a base business module cannot shadow a same-named test suite in an
        // extension.
        Set<String> suiteNames = new LinkedHashSet<>();
        collectSuitesFromConfig(baseConfig, baseProject, suites, suiteNames, limit);
        if (suites.size() < limit)
        {
            collectExtensionSuites(baseProject, suites, suiteNames, limit);
        }
        return ListYaxunitTestsSupport.buildResponse(projectName, suites);
    }

    /**
     * Collects suites from the common modules of {@code config}, deduplicated by module
     * name against {@code suiteNames}. Stops at {@code limit}. Never throws: an
     * unreadable module is simply skipped.
     */
    private static void collectSuitesFromConfig(Configuration config, IProject owner,
        List<Suite> suites, Set<String> suiteNames, int limit)
    {
        for (CommonModule cm : config.getCommonModules())
        {
            if (suites.size() >= limit)
            {
                break;
            }
            String name = cm.getName();
            if (name == null || name.isEmpty() || suiteNames.contains(name))
            {
                continue;
            }
            String modulePath = COMMON_MODULE_FOLDER + "/" + name + "/" + MODULE_FILE; //$NON-NLS-1$ //$NON-NLS-2$
            List<String> tests = collectTestMethods(owner, modulePath);
            if (tests.isEmpty())
            {
                continue;
            }
            suiteNames.add(name);
            suites.add(new Suite(name, modulePath, MODULE_TYPE, PARENT_TYPE, tests));
        }
    }

    /**
     * Collects suites from every extension project that derives from {@code baseProject}
     * (i.e. whose resolved base is that project), so test modules living in a
     * {@code *.YAXUNIT} extension are found even though they are NOT common modules of
     * the base configuration.
     */
    private static void collectExtensionSuites(IProject baseProject,
        List<Suite> suites, Set<String> suiteNames, int limit)
    {
        for (IProject project : ProjectContext.allProjects())
        {
            if (suites.size() >= limit)
            {
                break;
            }
            if (project == null || project == baseProject || !project.isAccessible())
            {
                continue;
            }
            if (!ExtensionOriginUtils.isExtensionProject(project))
            {
                continue;
            }
            IProject projectBase = ExtensionOriginUtils.resolveBaseProject(project);
            if (projectBase == null || !projectBase.equals(baseProject))
            {
                continue;
            }
            IV8Project v8 = Activator.getDefault().getV8ProjectManager().getProject(project);
            if (!(v8 instanceof IExtensionProject extension) || extension.getConfiguration() == null)
            {
                continue;
            }
            collectSuitesFromConfig(extension.getConfiguration(), project,
                suites, suiteNames, limit);
        }
    }

    /**
     * Reads one common module's BSL source and returns the names of the YAXUnit test
     * methods it registers ({@code ДобавитьТест("...")} arguments within a
     * {@code ДобавитьТестовыйНабор(...)} registration), in declaration order. An
     * unreadable module, or one that registers no test set, yields an empty list (the
     * module is simply skipped) — never throws.
     */
    private static List<String> collectTestMethods(IProject project, String modulePath)
    {
        try
        {
            IFile file = BslModuleUtils.resolveModuleFile(project, modulePath);
            if (file == null || !file.exists())
            {
                return List.of();
            }
            String source = BslModuleUtils.readFileText(file);
            if (!ListYaxunitTestsSupport.isTestSuiteSource(source))
            {
                return List.of();
            }
            return ListYaxunitTestsSupport.extractTestNames(source);
        }
        catch (Exception e) // NOSONAR an unreadable module is skipped, never thrown
        {
            return List.of();
        }
    }
}
