/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.*;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import com.ditrix.edt.mcp.server.protocol.GsonProvider;
import com.ditrix.edt.mcp.server.tools.IMcpTool.ResponseType;

/**
 * Lightweight tests for {@link GenerateTranslationStringsTool} that exercise
 * tool metadata, JSON schema, and the argument-validation guards that fire
 * BEFORE any project resolution or LanguageTool access — so they run headless.
 * <p>
 * {@code execute()} checks {@code projectName}, {@code targetLanguages}, and the
 * FROM_PROVIDER/{@code providerId} rule up front (before {@code ProjectContext.of});
 * everything past that needs a live EDT workspace and is covered by the E2E suite.
 */
public class GenerateTranslationStringsToolTest
{
    @Test
    public void testName()
    {
        assertEquals("generate_translation_strings", new GenerateTranslationStringsTool().getName()); //$NON-NLS-1$
    }

    @Test
    public void testResponseType()
    {
        assertEquals(ResponseType.MARKDOWN, new GenerateTranslationStringsTool().getResponseType());
    }

    @Test
    public void testDescriptionNotEmpty()
    {
        String desc = new GenerateTranslationStringsTool().getDescription();
        assertNotNull(desc);
        assertFalse(desc.isEmpty());
    }

    @Test
    public void testGuideHoldsMigratedDetail()
    {
        // The exhaustive parameter/mode detail moved from getDescription()/
        // getInputSchema() into getGuide(); assert it is non-empty and still
        // carries the migrated specifics (the FROM_PROVIDER rule and a mode value).
        String guide = new GenerateTranslationStringsTool().getGuide();
        assertNotNull(guide);
        assertFalse(guide.isEmpty());
        assertTrue("guide must explain the FROM_PROVIDER providerId rule", //$NON-NLS-1$
            guide.contains("FROM_PROVIDER")); //$NON-NLS-1$
        assertTrue("guide must list collectModelType modes", //$NON-NLS-1$
            guide.contains("COMPUTED_ONLY")); //$NON-NLS-1$
    }

    @Test
    public void testInputSchemaContainsAllParameters()
    {
        @SuppressWarnings("unchecked")
        Map<String, Object> parsed =
            GsonProvider.get().fromJson(new GenerateTranslationStringsTool().getInputSchema(), Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) parsed.get("properties"); //$NON-NLS-1$
        assertNotNull("schema must declare properties", properties); //$NON-NLS-1$
        assertTrue(properties.containsKey("projectName")); //$NON-NLS-1$
        assertTrue(properties.containsKey("targetLanguages")); //$NON-NLS-1$
        assertTrue(properties.containsKey("storageId")); //$NON-NLS-1$
        assertTrue(properties.containsKey("collectInterface")); //$NON-NLS-1$
        assertTrue(properties.containsKey("collectModel")); //$NON-NLS-1$
        assertTrue(properties.containsKey("collectModelType")); //$NON-NLS-1$
        assertTrue(properties.containsKey("fillUpType")); //$NON-NLS-1$
        assertTrue(properties.containsKey("providerId")); //$NON-NLS-1$
    }

    @Test
    public void testRequiredArrayMarksOnlyMandatoryParams()
    {
        // Parse the schema and verify the required array contains exactly
        // the mandatory parameters. Asserting on the parsed structure (rather
        // than substring-matching the JSON text) keeps the test stable across
        // serialization-order changes in JsonSchemaBuilder.
        @SuppressWarnings("unchecked")
        Map<String, Object> parsed =
            GsonProvider.get().fromJson(new GenerateTranslationStringsTool().getInputSchema(), Map.class);
        @SuppressWarnings("unchecked")
        List<String> required = (List<String>) parsed.get("required"); //$NON-NLS-1$
        assertNotNull("schema must declare required array", required); //$NON-NLS-1$
        assertEquals("required must be exactly [projectName, targetLanguages]", //$NON-NLS-1$
            Arrays.asList("projectName", "targetLanguages"), required); //$NON-NLS-1$ //$NON-NLS-2$
    }

    // ==================== Argument validation (returns before any project/LangTool access) ===========

    @Test
    public void testExecuteMissingProjectNameIsError()
    {
        Map<String, String> params = new HashMap<>();
        params.put("targetLanguages", "[\"en\"]"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new GenerateTranslationStringsTool().execute(params);
        assertTrue("missing projectName must produce 'projectName is required'", //$NON-NLS-1$
            result.contains("projectName is required")); //$NON-NLS-1$
    }

    @Test
    public void testExecuteMissingTargetLanguagesIsError()
    {
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "MyConfig"); //$NON-NLS-1$ //$NON-NLS-2$
        // No targetLanguages — the second guard fires before any project lookup.
        String result = new GenerateTranslationStringsTool().execute(params);
        assertTrue("missing targetLanguages must be reported as required", //$NON-NLS-1$
            result.contains("targetLanguages is required")); //$NON-NLS-1$
    }

    @Test
    public void testExecuteEmptyTargetLanguagesArrayIsError()
    {
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "MyConfig"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("targetLanguages", "[]"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new GenerateTranslationStringsTool().execute(params);
        assertTrue("an empty targetLanguages array must be reported as required", //$NON-NLS-1$
            result.contains("targetLanguages is required")); //$NON-NLS-1$
    }

    @Test
    public void testExecuteFromProviderWithoutProviderIdIsError()
    {
        // fillUpType=FROM_PROVIDER with no providerId is rejected up front, before
        // the project is resolved — so this branch is reachable headless.
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "MyConfig"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("targetLanguages", "[\"en\"]"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("fillUpType", "FROM_PROVIDER"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new GenerateTranslationStringsTool().execute(params);
        assertTrue("FROM_PROVIDER without providerId must mention providerId", //$NON-NLS-1$
            result.contains("providerId is required")); //$NON-NLS-1$
        assertTrue("the error must name FROM_PROVIDER", result.contains("FROM_PROVIDER")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testValidationErrorPayloadIsJson()
    {
        // The pre-flight guards return ToolResult.error(...).toJson(), delivered as a
        // structured error regardless of the tool's MARKDOWN response type.
        Map<String, String> params = new HashMap<>();
        String result = new GenerateTranslationStringsTool().execute(params);
        assertTrue("error payload must be JSON", result.trim().startsWith("{")); //$NON-NLS-1$ //$NON-NLS-2$
    }
}
