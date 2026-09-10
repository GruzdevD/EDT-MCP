/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 *
 * edt-mcp-vanessa: unit tests for the pure helpers behind {@code list_yaxunit_tests}.
 */

package com.ozon.edt.mcp.server.tools.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.ozon.edt.mcp.server.utils.ListYaxunitTestsSupport;
import com.ozon.edt.mcp.server.utils.ListYaxunitTestsSupport.Suite;

/**
 * Unit tests for {@link ListYaxunitTestsTool}'s pure helpers: the ЮТ-style suite/test
 * source detectors and the JSON-envelope builder. The EDT-driven AST walk (module
 * enumeration across base + extensions) is verified live in EDT.
 */
public class ListYaxunitTestsToolTest
{
    // -------------------------------------------------- isTestSuiteSource

    /** A faithful slice of an {@code ИсполняемыеСценарии()} registration body. */
    private static final String SUITE_SOURCE =
          "\tЮТТесты\n"
        + "\t\t.ВТранзакции(Истина)\n"
        + "\t\t.ДобавитьТестовыйНабор(\"Общий модуль OZON_Scabbers\")\n"
        + "\t\t\t.ДобавитьТест(\"ОтправляемыеПлатежныеПоручения\")\n"
        + "\t\t\t\t.СПараметрами(\"ПервичнаяОтправка\")\n"
        + "\t\t\t.ДобавитьТест(\"ПодписьHMAC\")\n"
        + "\t;\n";

    @Test
    public void testIsTestSuiteSourceDetectsRegistration()
    {
        assertTrue(ListYaxunitTestsSupport.isTestSuiteSource(SUITE_SOURCE));
    }

    @Test
    public void testIsTestSuiteSourceRejectsPlainModule()
    {
        assertFalse(ListYaxunitTestsSupport.isTestSuiteSource(
            "Процедура Рассчитать() Экспорт\nКонецПроцедуры"));
        assertFalse(ListYaxunitTestsSupport.isTestSuiteSource(null));
        assertFalse(ListYaxunitTestsSupport.isTestSuiteSource(""));
    }

    // -------------------------------------------------- extractTestNames

    @Test
    public void testExtractTestNamesCollectsRegisteredTestsInOrder()
    {
        List<String> names = ListYaxunitTestsSupport.extractTestNames(SUITE_SOURCE);
        assertEquals(Arrays.asList("ОтправляемыеПлатежныеПоручения", "ПодписьHMAC"), names);
    }

    @Test
    public void testExtractTestNamesParameterizedCollapsesToOne()
    {
        String src = "\t\t.ДобавитьТест(\"СПараметрамиВариант\")\n"
            + "\t\t\t.СПараметрами(Истина)\n"
            + "\t\t\t.СПараметрами(Ложь)\n";
        assertEquals(Collections.singletonList("СПараметрамиВариант"),
            ListYaxunitTestsSupport.extractTestNames(src));
    }

    @Test
    public void testExtractTestNamesDeduplicates()
    {
        assertEquals(Collections.singletonList("X"),
            ListYaxunitTestsSupport.extractTestNames(".ДобавитьТест(\"X\").ДобавитьТест(\"X\")"));
    }

    @Test
    public void testExtractTestNamesHandlesNullAndGarbage()
    {
        assertTrue(ListYaxunitTestsSupport.extractTestNames(null).isEmpty());
        assertTrue(ListYaxunitTestsSupport.extractTestNames("no tests here").isEmpty());
    }

    // ------------------------------------------------------------ buildResponse

    @Test
    public void testBuildResponseEnvelopePreservesOrder()
    {
        Suite suite1 = new Suite("OZON_Тесты", "CommonModules/OZON_Тесты/Module.bsl", //$NON-NLS-1$
            "Module", "OZON_Тесты", Arrays.asList("Б", "А")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        Suite suite2 = new Suite("ДругиеТесты", "CommonModules/ДругиеТесты/Module.bsl", //$NON-NLS-1$
            "Module", "ДругиеТесты", Collections.singletonList("Тест1")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        String json =
            ListYaxunitTestsSupport.buildResponse("afm", Arrays.asList(suite1, suite2)); //$NON-NLS-1$

        JsonElement root = JsonParser.parseString(json);
        assertTrue(root.isJsonObject());
        assertEquals("afm", root.getAsJsonObject().get("project").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(2, root.getAsJsonObject().get("count").getAsInt()); //$NON-NLS-1$
        assertEquals(3, root.getAsJsonObject().get("testCount").getAsInt()); //$NON-NLS-1$

        JsonArray suites = root.getAsJsonObject().get("suites").getAsJsonArray(); //$NON-NLS-1$
        assertEquals(2, suites.size());
        assertEquals("OZON_Тесты", //$NON-NLS-1$
            suites.get(0).getAsJsonObject().get("moduleName").getAsString()); //$NON-NLS-1$
        assertEquals("CommonModules/OZON_Тесты/Module.bsl", //$NON-NLS-1$
            suites.get(0).getAsJsonObject().get("modulePath").getAsString()); //$NON-NLS-1$
        JsonArray tests1 = suites.get(0).getAsJsonObject().get("tests").getAsJsonArray(); //$NON-NLS-1$
        assertEquals("Б", tests1.get(0).getAsJsonObject().get("name").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("А", tests1.get(1).getAsJsonObject().get("name").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testBuildResponseEmptyAndDeduplicatesTests()
    {
        Suite dedup = new Suite("X", "X/Module.bsl", "Module", "X", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            Arrays.asList("Т", "Т")); //$NON-NLS-1$ //$NON-NLS-2$
        String json = ListYaxunitTestsSupport.buildResponse("p", Arrays.asList(dedup)); //$NON-NLS-1$
        JsonArray tests = JsonParser.parseString(json).getAsJsonObject().get("suites") //$NON-NLS-1$
            .getAsJsonArray().get(0).getAsJsonObject().get("tests").getAsJsonArray(); //$NON-NLS-1$
        assertEquals(1, tests.size());

        String empty = ListYaxunitTestsSupport.buildResponse("p", Collections.emptyList()); //$NON-NLS-1$
        assertEquals(0, JsonParser.parseString(empty).getAsJsonObject().get("count").getAsInt()); //$NON-NLS-1$ //$NON-NLS-2$
    }
}
