/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 *
 * edt-mcp-vanessa: unit tests for the SWT-free helpers of the Test-Selection view.
 */

package com.ditrix.edt.mcp.server.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import com.ditrix.edt.mcp.server.ui.TestSelectionSupport.FeatureRow;
import com.ditrix.edt.mcp.server.ui.TestSelectionSupport.ModuleRow;
import com.ditrix.edt.mcp.server.ui.TestSelectionSupport.SuiteRow;
import com.ditrix.edt.mcp.server.ui.TestSelectionSupport.TestRow;

/**
 * Unit tests for {@link TestSelectionSupport}: the wire-contract parsing (the
 * Markdown table of {@code list_modules}, the JSON envelope of
 * {@code vanessa_list_features}), parameter building for the two run tools, and the
 * pending/job/status/report extractors. The SWT view itself is verified live.
 */
public class TestSelectionSupportTest
{
    // ------------------------------------------------------------ parseModules

    private static final String MODULES_MARKDOWN =
        "## BSL Modules: afm\n\n"
            + "**Total:** 2 modules\n\n"
            + "| Module Path | Module Type | Parent Type | Parent Name |\n"
            + "| --- | --- | --- | --- |\n"
            + "| CommonModules/OZON_Тесты/Module.bsl | Module | CommonModule | OZON_Тесты |\n"
            + "| Documents/Invoice/ObjectModule.bsl | ObjectModule | Document | Invoice |\n";

    @Test
    public void testParseModulesSkipsHeaderSeparatorAndProse()
    {
        List<ModuleRow> rows = TestSelectionSupport.parseModules(MODULES_MARKDOWN);
        assertEquals(2, rows.size());
        assertEquals("CommonModules/OZON_Тесты/Module.bsl", rows.get(0).modulePath); //$NON-NLS-1$
        assertEquals("Module", rows.get(0).moduleType); //$NON-NLS-1$
        assertEquals("OZON_Тесты", rows.get(0).parentName); //$NON-NLS-1$
        assertEquals("Documents/Invoice/ObjectModule.bsl", rows.get(1).modulePath); //$NON-NLS-1$
    }

    @Test
    public void testParseModulesNullYieldsEmpty()
    {
        assertTrue(TestSelectionSupport.parseModules(null).isEmpty());
        assertTrue(TestSelectionSupport.parseModules("No table here").isEmpty()); //$NON-NLS-1$
    }

    @Test
    public void testParseModulesUnescapesEscapedPipe()
    {
        String md = "| CommonModules/Foo\\|Bar/Module.bsl | Module | CommonModule | Foo\\|Bar |\n"; //$NON-NLS-1$
        List<ModuleRow> rows = TestSelectionSupport.parseModules(md);
        assertEquals(1, rows.size());
        assertEquals("CommonModules/Foo|Bar/Module.bsl", rows.get(0).modulePath); //$NON-NLS-1$
    }

    // ------------------------------------------------------------ parseFeatures

    private static final String FEATURES_JSON =
        "{\"root\":\"/x\",\"count\":2,\"features\":["
            + "{\"path\":\"/x/a.feature\",\"featureName\":\"Создание справочника\",\"tags\":\"smoke\"},"
            + "{\"path\":\"/x/b.feature\",\"featureName\":\"Printer\",\"tags\":\"\"}"
            + "]}";

    @Test
    public void testParseFeaturesReadsJsonEnvelope()
    {
        List<FeatureRow> rows = TestSelectionSupport.parseFeatures(FEATURES_JSON);
        assertEquals(2, rows.size());
        assertEquals("/x/a.feature", rows.get(0).path); //$NON-NLS-1$
        assertEquals("Создание справочника", rows.get(0).featureName); //$NON-NLS-1$
        assertEquals("smoke", rows.get(0).tags); //$NON-NLS-1$
        assertEquals("", rows.get(1).tags); //$NON-NLS-1$
    }

    @Test
    public void testParseFeaturesHandlesMalformed()
    {
        assertTrue(TestSelectionSupport.parseFeatures(null).isEmpty());
        assertTrue(TestSelectionSupport.parseFeatures("not json").isEmpty()); //$NON-NLS-1$
        assertTrue(TestSelectionSupport.parseFeatures("{\"features\":\"nope\"}").isEmpty()); //$NON-NLS-1$
    }

    // -------------------------------------------------- context enumeration

    @Test
    public void testParseConfigNamesReadsJsonEnvelope()
    {
        String json = "{\"success\":true,\"configurations\":[" //$NON-NLS-1$
            + "{\"name\":\"actest\",\"type\":\"1C:Enterprise.RuntimeClient\"}," //$NON-NLS-1$
            + "{\"name\":\"vabdd\"}],\"count\":2}"; //$NON-NLS-1$
        assertEquals(Arrays.asList("actest", "vabdd"), //$NON-NLS-1$ //$NON-NLS-2$
            TestSelectionSupport.parseConfigNames(json));
    }

    @Test
    public void testParseConfigNamesHandlesMalformed()
    {
        assertTrue(TestSelectionSupport.parseConfigNames(null).isEmpty());
        assertTrue(TestSelectionSupport.parseConfigNames("not json").isEmpty()); //$NON-NLS-1$
        assertTrue(TestSelectionSupport.parseConfigNames("{\"configurations\":[]}").isEmpty()); //$NON-NLS-1$
    }

    // ------------------------------------------------------------ params build

    @Test
    public void testBuildYaxunitParamsKeepsContextAddsModulesAndTimeout()
    {
        Map<String, String> base = new LinkedHashMap<>();
        base.put("launchConfigurationName", "actest"); //$NON-NLS-1$ //$NON-NLS-2$
        Map<String, String> params =
            TestSelectionSupport.buildYaxunitParams(base, Arrays.asList("A/Module.bsl", "B/Module.bsl")); //$NON-NLS-1$
        assertEquals("actest", params.get("launchConfigurationName")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("A/Module.bsl,B/Module.bsl", params.get("modules")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("45", params.get("timeout")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testBuildBddRunParamsAddsFeature()
    {
        Map<String, String> base = new LinkedHashMap<>();
        base.put("project", "afm"); //$NON-NLS-1$
        base.put("launchConfigurationName", "vabdd"); //$NON-NLS-1$ //$NON-NLS-2$
        Map<String, String> params =
            TestSelectionSupport.buildBddRunParams(base, "/x/a.feature"); //$NON-NLS-1$
        assertEquals("/x/a.feature", params.get("feature")); //$NON-NLS-1$
        assertEquals("afm", params.get("project")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    // ------------------------------------------------------------ extractors

    @Test
    public void testExtractJobIdFromPendingMarkdown()
    {
        String pending = "**Pending:** YAXUnit work continues in background job `job-42`."; //$NON-NLS-1$
        assertEquals("job-42", TestSelectionSupport.extractJobId(pending)); //$NON-NLS-1$
        assertNull(TestSelectionSupport.extractJobId("**Total:** 3 tests, 3 passed")); //$NON-NLS-1$
        assertNull(TestSelectionSupport.extractJobId(null)); //$NON-NLS-1$
    }

    @Test
    public void testExtractJobStatusFromRender()
    {
        assertEquals("done", TestSelectionSupport.extractJobStatus( //$NON-NLS-1$
            "# Background job: done\n\n...\n| status | done |")); //$NON-NLS-1$
        assertEquals("running", TestSelectionSupport.extractJobStatus( //$NON-NLS-1$
            "# Background job: running\n\n## Progress")); //$NON-NLS-1$
        assertEquals("failed", TestSelectionSupport.extractJobStatus( //$NON-NLS-1$
            "\n| status | failed |\n")); //$NON-NLS-1$
        assertNull(TestSelectionSupport.extractJobStatus("no status here")); //$NON-NLS-1$
        assertNull(TestSelectionSupport.extractJobStatus(null)); //$NON-NLS-1$
    }

    @Test
    public void testExtractLaunchIdHandlesIntegerShapedNumber()
    {
        assertEquals("7", TestSelectionSupport.extractLaunchId("{\"launchId\":7,\"status\":\"launching\"}")); //$NON-NLS-1$
        assertNull(TestSelectionSupport.extractLaunchId("{\"status\":\"nope\"}")); //$NON-NLS-1$
        assertNull(TestSelectionSupport.extractLaunchId("garbage")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    // ------------------------------------------------------- yaxunit suites / run

    private static final String SUITES_JSON =
        "{\"project\":\"afm\",\"count\":1,\"testCount\":2,\"suites\":["
        + "{\"moduleName\":\"ОМ_OZON_Scabbers\",\"modulePath\":"
        + "\"CommonModules/ОМ_OZON_Scabbers/Module.bsl\",\"moduleType\":\"Module\","
        + "\"parentName\":\"CommonModule\","
        + "\"tests\":[{\"name\":\"ПодписьHMAC\"},{\"name\":\"ОтправитьПлатежныеПоручения\"}]}"
        + "]}";

    @Test
    public void testParseYaxunitSuitesReadsModuleNameAndTests()
    {
        List<SuiteRow> rows = TestSelectionSupport.parseYaxunitSuites(SUITES_JSON);
        assertEquals(1, rows.size());
        assertEquals("ОМ_OZON_Scabbers", rows.get(0).moduleName); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("CommonModules/ОМ_OZON_Scabbers/Module.bsl", rows.get(0).modulePath); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("Module", rows.get(0).moduleType); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("CommonModule", rows.get(0).parentName); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(Arrays.asList("ПодписьHMAC", "ОтправитьПлатежныеПоручения"), //$NON-NLS-1$ //$NON-NLS-2$
            rows.get(0).tests);
    }

    @Test
    public void testParseYaxunitSuitesHandlesMalformed()
    {
        assertTrue(TestSelectionSupport.parseYaxunitSuites(null).isEmpty());
        assertTrue(TestSelectionSupport.parseYaxunitSuites("not json").isEmpty()); //$NON-NLS-1$
        assertTrue(TestSelectionSupport.parseYaxunitSuites("{\"suites\":\"nope\"}").isEmpty()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static SuiteRow suite(String moduleName, String... tests)
    {
        return new SuiteRow(moduleName, "CommonModules/" + moduleName + "/Module.bsl", //$NON-NLS-1$ //$NON-NLS-2$
            "Module", "CommonModule", Arrays.asList(tests)); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testBuildYaxunitRunParamsSuitesOnlyUsesModuleNames()
    {
        Map<String, String> base = new LinkedHashMap<>();
        base.put("launchConfigurationName", "actest"); //$NON-NLS-1$ //$NON-NLS-2$
        SuiteRow s1 = suite("ОМ_OZON_Scabbers", "ПодписьHMAC", "ОтправитьПлатежныеПоручения"); //$NON-NLS-1$ //$NON-NLS-2$
        SuiteRow s2 = suite("ОМ_OZON_Другое"); //$NON-NLS-1$
        Map<String, String> params =
            TestSelectionSupport.buildYaxunitRunParams(base, Arrays.asList(s1, s2), List.of());
        assertEquals("ОМ_OZON_Scabbers,ОМ_OZON_Другое", params.get("modules")); //$NON-NLS-1$ //$NON-NLS-2$
        assertNull(params.get("tests")); //$NON-NLS-1$
        assertEquals("actest", params.get("launchConfigurationName")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testBuildYaxunitRunParamsTestsOnlyUsesModuleMethodTokens()
    {
        Map<String, String> params = TestSelectionSupport.buildYaxunitRunParams(
            new LinkedHashMap<>(),
            List.of(),
            Arrays.asList(new TestRow("ОМ_OZON_Scabbers", "ПодписьHMAC"), //$NON-NLS-1$ //$NON-NLS-2$
                new TestRow("ОМ_OZON_Scabbers", "ОтправитьПлатежныеПоручения"))); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("ОМ_OZON_Scabbers.ПодписьHMAC," //$NON-NLS-1$ //$NON-NLS-2$
            + "ОМ_OZON_Scabbers.ОтправитьПлатежныеПоручения", params.get("tests")); //$NON-NLS-1$ //$NON-NLS-2$
        assertNull(params.get("modules")); //$NON-NLS-1$
    }

    @Test
    public void testBuildYaxunitRunParamsBothExpandsSuitesUnionsExplicit()
    {
        SuiteRow s1 = suite("М1", "A", "B"); //$NON-NLS-1$ //$NON-NLS-2$
        Map<String, String> params = TestSelectionSupport.buildYaxunitRunParams(
            new LinkedHashMap<>(),
            Collections.singletonList(s1),
            Arrays.asList(new TestRow("М1", "A"), new TestRow("М2", "C"))); //$NON-NLS-1$ //$NON-NLS-2$
        // suite М1 expands to М1.A,М1.B; the explicit М1.A is already covered (deduped);
        // the cross-suite М2.C is added.
        assertEquals("М1.A,М1.B,М2.C", params.get("tests")); //$NON-NLS-1$ //$NON-NLS-2$
        assertNull(params.get("modules")); //$NON-NLS-1$
    }

    @Test
    public void testExtractToolErrorReadsErrorEnvelopeOnly()
    {
        assertEquals("Project not found: NoSuchProject", //$NON-NLS-1$
            TestSelectionSupport.extractToolError("{\"error\":\"Project not found: NoSuchProject\"}")); //$NON-NLS-1$ //$NON-NLS-2$
        assertNull(TestSelectionSupport.extractToolError(SUITES_JSON));
        assertNull(TestSelectionSupport.extractToolError("garbage")); //$NON-NLS-1$ //$NON-NLS-2$
        assertNull(TestSelectionSupport.extractToolError(null));
    }

    @Test
    public void testExtractStatusAndReport()
    {
        assertEquals("running", TestSelectionSupport.extractExecutionStatus( //$NON-NLS-1$
            "{\"launchId\":7,\"status\":\"running\"}")); //$NON-NLS-1$
        assertNull(TestSelectionSupport.extractExecutionStatus("{}")); //$NON-NLS-1$

        assertEquals("**Verdict:** pass", TestSelectionSupport.extractReport( //$NON-NLS-1$
            "{\"report\":\"**Verdict:** pass\"}")); //$NON-NLS-1$
        assertNull(TestSelectionSupport.extractReport("{\"report\":null}")); //$NON-NLS-1$
        assertFalse(TestSelectionSupport.extractReport("garbage") != null); //$NON-NLS-1$
    }
}
