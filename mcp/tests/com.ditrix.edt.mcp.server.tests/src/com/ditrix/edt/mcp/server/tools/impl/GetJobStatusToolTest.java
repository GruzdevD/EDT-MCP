/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.ditrix.edt.mcp.server.tools.IMcpTool.ResponseType;
import com.ditrix.edt.mcp.server.utils.BackgroundJobs;
import com.ditrix.edt.mcp.server.utils.BackgroundJobs.JobSnapshot;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Contract and headless polling tests for {@link GetJobStatusTool}. */
public class GetJobStatusToolTest
{
    @Test
    public void referenceDeadlinePublishesStructuredResultWhileWorkerUnwinds() throws Exception
    {
        var entered = new java.util.concurrent.CountDownLatch(1);
        var released = new java.util.concurrent.CountDownLatch(1);
        try (var jobs = new BackgroundJobs(4, 1))
        {
            var tool = new GetJobStatusTool(jobs);
            var partial = Map.of("complete", false, "stopReason", "timeout", "references", java.util.List.of("partial"));
            var job = jobs.startWithTimeoutResult(FindReferencesTool.NAME, 1_500, 1, "test", () -> partial, progress -> {
                entered.countDown();
                while (released.getCount() != 0)
                {
                    try
                    {
                        released.await();
                    }
                    catch (InterruptedException e)
                    {
                        // Model call still unwinding.
                    }
                }
                return Map.of("stopReason", "late");
            });
            try
            {
                assertTrue("The worker must start before its deadline",
                    entered.await(5, java.util.concurrent.TimeUnit.SECONDS));
                var params = Map.of("jobId", job.getId(), "waitSeconds", "0");
                assertEquals(ResponseType.JSON, tool.getResponseType(params));
                var first = com.google.gson.JsonParser.parseString(tool.execute(params)).getAsJsonObject();
                assertEquals(job.getId(), first.get("jobId").getAsString());
                var reply = com.google.gson.JsonParser.parseString(tool.execute(
                    Map.of("jobId", job.getId(), "waitSeconds", "5"))).getAsJsonObject();
                var done = jobs.get(job.getId());
                assertEquals(BackgroundJobs.Status.DONE, done.getStatus());
                assertEquals(partial, done.getResult());
                assertEquals("timeout", reply.getAsJsonObject("result").get("stopReason").getAsString());
                assertEquals(done.getElapsedMs(), reply.get("elapsedMillis").getAsLong());
                assertTrue(jobs.startWithTimeoutResult(FindReferencesTool.NAME, 1000, 1, "test", () -> partial,
                    progress -> partial) == null);
            }
            finally
            {
                released.countDown();
            }
        }
    }
    private BackgroundJobs jobs;
    private GetJobStatusTool tool;

    @Before
    public void setUp()
    {
        jobs = new BackgroundJobs(20, 2);
        tool = new GetJobStatusTool(jobs);
    }

    @After
    public void tearDown()
    {
        jobs.close();
    }

    @Test
    public void testContract()
    {
        assertEquals(GetJobStatusTool.NAME, tool.getName());
        assertEquals(ResponseType.MARKDOWN, tool.getResponseType());
        assertTrue(tool.getDescription().contains("get_tool_guide('get_job_status')")); //$NON-NLS-1$

        JsonObject schema = JsonParser.parseString(tool.getInputSchema()).getAsJsonObject();
        JsonObject properties = schema.getAsJsonObject("properties"); //$NON-NLS-1$
        assertTrue(properties.has("jobId")); //$NON-NLS-1$
        assertTrue(properties.has("waitSeconds")); //$NON-NLS-1$
        assertTrue(schema.getAsJsonArray("required").contains( //$NON-NLS-1$
            JsonParser.parseString("\"jobId\""))); //$NON-NLS-1$
        assertFalse(schema.getAsJsonArray("required").contains( //$NON-NLS-1$
            JsonParser.parseString("\"waitSeconds\""))); //$NON-NLS-1$
        String waitDescription = properties.getAsJsonObject("waitSeconds") //$NON-NLS-1$
            .get("description").getAsString(); //$NON-NLS-1$
        assertTrue(waitDescription.contains("0 to " + AskWorkmateTool.MAX_WAIT_SECONDS)); //$NON-NLS-1$
    }

    @Test
    public void testMissingAndBlankJobIdAreActionable()
    {
        assertContains(tool.execute(Map.of()), "jobId is required", "tool that started"); //$NON-NLS-1$ //$NON-NLS-2$
        assertContains(tool.execute(Map.of("jobId", "   ")), //$NON-NLS-1$ //$NON-NLS-2$
            "jobId", "non-empty", "tool that started"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    @Test
    public void testUnknownJobIdIsActionable()
    {
        assertContains(tool.execute(Map.of("jobId", "missing-job-42", "waitSeconds", "0")), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            "Unknown or expired jobId 'missing-job-42'", "tool that originally created", //$NON-NLS-1$ //$NON-NLS-2$
            "get_job_status"); //$NON-NLS-1$
    }

    @Test
    public void testWaitUsesAskWorkmateTransportBound()
    {
        Map<String, String> params = new HashMap<>();
        params.put("jobId", "any"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("waitSeconds", //$NON-NLS-1$
            Integer.toString(AskWorkmateTool.MAX_WAIT_SECONDS + 1));
        assertContains(tool.execute(params), "waitSeconds", //$NON-NLS-1$
            "0 to " + AskWorkmateTool.MAX_WAIT_SECONDS, "return immediately"); //$NON-NLS-1$ //$NON-NLS-2$

        params.put("waitSeconds", "-1"); //$NON-NLS-1$ //$NON-NLS-2$
        assertContains(tool.execute(params), "waitSeconds", "return immediately"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testDoneSnapshotIncludesOwnerProgressAndResult()
    {
        JobSnapshot started = jobs.start("test_owner", 5_000L, "Accepted | safely", progress -> { //$NON-NLS-1$ //$NON-NLS-2$
            progress.add("Finished work"); //$NON-NLS-1$
            return "terminal result"; //$NON-NLS-1$
        });
        jobs.await(started.getId(), 2_000L);

        String result = tool.execute(Map.of("jobId", started.getId(), "waitSeconds", "0")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertContains(result, "# Background job: done", "| owningTool | test_owner |", //$NON-NLS-1$ //$NON-NLS-2$
            "Accepted | safely", "Finished work", "## Result", "terminal result"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
    }

    private static void assertContains(String value, String... expected)
    {
        for (String part : expected)
        {
            assertTrue("Expected '" + part + "' in: " + value, value.contains(part)); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }
}
