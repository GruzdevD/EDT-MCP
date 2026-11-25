/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import com.ditrix.edt.mcp.server.protocol.GsonProvider;
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.McpKeys;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.tools.reference.MetadataReferenceService;
import com.ditrix.edt.mcp.server.tools.reference.ReferenceSearchResult;
import com.ditrix.edt.mcp.server.tools.reference.ReferenceSourceScope;
import com.ditrix.edt.mcp.server.tools.reference.ScopedReferenceSearch;
import com.ditrix.edt.mcp.server.utils.BackgroundJobPolling;
import com.ditrix.edt.mcp.server.utils.BackgroundJobRenderer;
import com.ditrix.edt.mcp.server.utils.BackgroundJobs;
import com.ditrix.edt.mcp.server.utils.MetadataTypeUtils;
import com.google.gson.JsonParseException;

/**
 * Tool to find all references to a metadata object.
 * Returns all places where the object is used: in other metadata objects and BSL code.
 */
@SuppressWarnings("restriction")
public class FindReferencesTool implements IMcpTool
{
    public static final String NAME = "find_references"; //$NON-NLS-1$

    /** Input param: FQN of the metadata object to find references to. */
    private static final String KEY_OBJECT_FQN = "objectFqn"; //$NON-NLS-1$
    private static final String SOURCE_SCOPE = "sourceScope"; //$NON-NLS-1$
    private static final String TIMEOUT = "timeoutSeconds";
    private static final String WAIT = "waitSeconds";
    private static final String LEGACY_JOB = "find_references_legacy";
    static final int DEFAULT_TIMEOUT_SECONDS = 45;
    private static final int MAX_TIMEOUT_SECONDS = 3600;
    private static final int DEFAULT_LIMIT = 100;
    private static final int MAX_LIMIT = 500;
    private static final int MAX_RUNNING = 4;

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Discover where a metadata object is used throughout the configuration and BSL code. " //$NON-NLS-1$
            + "Parameters and examples: get_tool_guide('find_references')."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty(McpKeys.PROJECT_NAME,
                "EDT project name (required)", true) //$NON-NLS-1$
            .stringProperty(KEY_OBJECT_FQN,
                "FQN of the object to search for, e.g. 'Catalog.Products' " + //$NON-NLS-1$
                "(type token may be English or Russian) (required)", true) //$NON-NLS-1$
            .integerProperty("limit", //$NON-NLS-1$
                "Caps the overall unique result count (default 100, max 500), not per category. Legacy Markdown uses limit*10.")
            .integerProperty(TIMEOUT, "Total search budget including index readiness (default 45, range 1–3600).")
            .integerProperty(WAIT, "Maximum wait for this reply (default 5, range 0–45). Use 0 to get a job ID.")
            .property(SOURCE_SCOPE, ReferenceSourceScope.inputSchema())
            .build();
    }

    @Override
    public ResponseType getResponseType()
    {
        return ResponseType.MARKDOWN;
    }

    @Override
    public ResponseType getResponseType(Map<String, String> params)
    {
        return structured(params) ? ResponseType.JSON : ResponseType.MARKDOWN;
    }

    private static boolean structured(Map<String, String> params)
    {
        return params != null && (params.containsKey(SOURCE_SCOPE)
            || params.containsKey(TIMEOUT) || params.containsKey(WAIT));
    }

    @Override
    public String getResultFileName(Map<String, String> params)
    {
        String objectFqn = JsonUtils.extractStringArgument(params, KEY_OBJECT_FQN);
        if (objectFqn != null && !objectFqn.isEmpty())
        {
            String safeName = objectFqn.replace(".", "-").toLowerCase(); //$NON-NLS-1$ //$NON-NLS-2$
            return "references-" + safeName + ".md"; //$NON-NLS-1$ //$NON-NLS-2$
        }
        return "references.md"; //$NON-NLS-1$
    }

    @Override
    public ToolResult validateRawArguments(Map<String, Object> arguments)
    {
        if (arguments.containsKey(SOURCE_SCOPE))
        {
            try
            {
                ReferenceSourceScope.parse(arguments.get(SOURCE_SCOPE));
            }
            catch (IllegalArgumentException e)
            {
                return ToolResult.error(e.getMessage());
            }
        }
        if (arguments.containsKey(TIMEOUT)
            && !validWireInteger(arguments.get(TIMEOUT), 1, MAX_TIMEOUT_SECONDS))
        {
            return integerError(TIMEOUT, 1, MAX_TIMEOUT_SECONDS);
        }
        if (arguments.containsKey(WAIT)
            && !validWireInteger(arguments.get(WAIT), 0, AskWorkmateTool.MAX_WAIT_SECONDS))
        {
            return integerError(WAIT, 0, AskWorkmateTool.MAX_WAIT_SECONDS);
        }
        return null;
    }

    private static boolean validWireInteger(Object value, int min, int max)
    {
        return value instanceof Number && readInteger(value.toString(), min, max) != null;
    }

    private static Integer readInteger(String value, int min, int max)
    {
        if (value == null)
        {
            return null;
        }
        try
        {
            var exact = new BigDecimal(value);
            if (exact.compareTo(BigDecimal.valueOf(min)) < 0 || exact.compareTo(BigDecimal.valueOf(max)) > 0)
            {
                return null;
            }
            return exact.intValueExact();
        }
        catch (NumberFormatException | ArithmeticException e)
        {
            return null;
        }
    }

    private static ToolResult integerError(String key, int min, int max)
    {
        return ToolResult.error(key + " must be a JSON integer from " + min + " to " + max + ".");
    }

    @Override
    public String execute(Map<String, String> params)
    {
        long started = System.nanoTime();
        // Validate required parameters via the shared guard (canonical reference
        // for the broader required-guard migration).
        String missing = JsonUtils.requireArgument(params, McpKeys.PROJECT_NAME);
        if (missing != null)
        {
            return missing;
        }
        missing = JsonUtils.requireArgument(params, KEY_OBJECT_FQN);
        if (missing != null)
        {
            return missing;
        }

        String projectName = JsonUtils.extractStringArgument(params, McpKeys.PROJECT_NAME);
        String objectFqn = JsonUtils.extractStringArgument(params, KEY_OBJECT_FQN);

        ReferenceSourceScope scope = null;
        if (params.containsKey(SOURCE_SCOPE))
        {
            try
            {
                String rawScope = JsonUtils.extractStringArgument(params, SOURCE_SCOPE);
                scope = ReferenceSourceScope.parse(GsonProvider.fromJson(rawScope, Object.class));
            }
            catch (JsonParseException e)
            {
                return ToolResult.error("sourceScope must be a JSON object with projectNames.").toJson(); //$NON-NLS-1$
            }
            catch (IllegalArgumentException e)
            {
                return ToolResult.error(e.getMessage()).toJson();
            }
        }

        int limit = Math.min(JsonUtils.extractIntArgument(params, "limit", DEFAULT_LIMIT), MAX_LIMIT);
        if (!structured(params))
        {
            return legacy(projectName, objectFqn, limit);
        }

        Integer timeout = params.containsKey(TIMEOUT) ? readInteger(params.get(TIMEOUT), 1, MAX_TIMEOUT_SECONDS)
            : Integer.valueOf(DEFAULT_TIMEOUT_SECONDS);
        if (timeout == null)
        {
            return integerError(TIMEOUT, 1, MAX_TIMEOUT_SECONDS).toJson();
        }
        Integer wait = BackgroundJobPolling.readWaitSeconds(params, WAIT,
            AskWorkmateTool.DEFAULT_WAIT_SECONDS, AskWorkmateTool.MAX_WAIT_SECONDS);
        if (wait == null)
        {
            return integerError(WAIT, 0, AskWorkmateTool.MAX_WAIT_SECONDS).toJson();
        }
        if (limit < 1)
        {
            return integerError("limit", 1, MAX_LIMIT).toJson();
        }
        return start(projectName, objectFqn, scope, limit, timeout, wait, started);
    }

    private static String start(String project, String fqn, ReferenceSourceScope scope,
        int limit, int timeout, int wait, long started)
    {
        var result = new ReferenceSearchResult(project, MetadataTypeUtils.normalizeFqn(fqn), scope, limit, timeout, started);
        BackgroundJobs jobs = BackgroundJobs.shared();
        try
        {
            var job = jobs.startWithTimeoutResult(NAME, result.remainingMillis(), MAX_RUNNING,
                "Accepted reference search.", result::timeout,
                progress -> new ScopedReferenceSearch().run(project, fqn, scope, result));
            if (job == null)
            {
                return busy();
            }
            var snapshot = BackgroundJobPolling.await(jobs, job.getId(), wait);
            if (snapshot == null)
            {
                return GetJobStatusTool.unknownJobError(job.getId());
            }
            if (wait > 0 && snapshot.getErrorMessage() != null)
            {
                return ToolResult.error(snapshot.getErrorMessage()).toJson();
            }
            return BackgroundJobRenderer.renderJson(snapshot);
        }
        catch (RejectedExecutionException e)
        {
            return busy();
        }
    }

    private static String legacy(String project, String fqn, int limit)
    {
        long budget = TimeUnit.SECONDS.toMillis(DEFAULT_TIMEOUT_SECONDS);
        long started = System.nanoTime();
        var stopped = new AtomicBoolean();
        java.util.function.BooleanSupplier deadline =
            () -> System.nanoTime() - started >= TimeUnit.MILLISECONDS.toNanos(budget);
        var partial = new AtomicReference<Supplier<String>>(
            () -> MetadataReferenceService.formatOutput(MetadataTypeUtils.normalizeFqn(fqn), List.of()));
        Supplier<Object> expired = () -> {
            stopped.set(true);
            return partial.get().get() + "\n> Search incomplete. The 45-second search budget expired.\n";
        };
        BackgroundJobs jobs = BackgroundJobs.shared();
        try
        {
            var job = jobs.startWithTimeoutResult(LEGACY_JOB, budget, MAX_RUNNING,
                "Accepted reference search.", expired, progress -> {
                    if (stopped.get() || deadline.getAsBoolean())
                    {
                        return expired.get();
                    }
                    String text = new MetadataReferenceService().findReferences(project, fqn, limit,
                        () -> stopped.get() || Thread.currentThread().isInterrupted() || deadline.getAsBoolean(),
                        partial::set);
                    return stopped.get() || deadline.getAsBoolean() ? expired.get() : text;
                });
            if (job == null)
            {
                return busy();
            }
            var snapshot = jobs.await(job.getId(), budget);
            if (snapshot != null && snapshot.getResult() instanceof String text)
            {
                return text;
            }
            if (snapshot != null && snapshot.getErrorMessage() != null)
            {
                return ToolResult.error(snapshot.getErrorMessage()).toJson();
            }
            return (String)expired.get();
        }
        catch (RejectedExecutionException e)
        {
            return busy();
        }
    }

    private static String busy()
    {
        return ToolResult.error("Reference search is busy. Wait for running jobs to finish, then retry.").toJson();
    }
}
