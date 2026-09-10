/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 *
 * edt-mcp-vanessa: pure, EDT-free helpers for {@code list_yaxunit_tests}.
 */

package com.ditrix.edt.mcp.server.utils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.ditrix.edt.mcp.server.protocol.GsonProvider;

/**
 * Pure (no BSL model / Eclipse dependencies) helpers behind the
 * {@code list_yaxunit_tests} tool, mirroring how {@code TestSelectionSupport} keeps
 * wire-contract logic unit-testable without an EDT runtime: the tool walks the model
 * and hands raw module/test strings here, and this class decides which of those are
 * YAXUnit tests and assembles the JSON envelope.
 *
 * <p>Projects in this org run YAXUnit in the {@code Ют} programmatic style: a test
 * suite is a common module whose exported {@code ИсполняемыеСценарии()} procedure
 * registers test sets through the fluent builder
 * {@code ЮТТесты...ДобавитьТестовыйНабор(...).ДобавитьТест("Имя")...}. A module is a
 * suite when its registration calls {@code ДобавитьТестовыйНабор(...)}; the tests of a
 * suite are the string-literal arguments of its {@code ДобавитьТест("...")} calls. The
 * {@code &Тест} pragma style is a different, unused-in-this-org model and is not
 * detected here. All matching is case-insensitive and dialect-agnostic.</p>
 *
 * <p>The Cyrillic identifiers below are spelled as backslash-u escapes in the regex
 * sources so the patterns survive a non-UTF-8 Tycho build (project convention).</p>
 */
public final class ListYaxunitTestsSupport
{
    /**
     * {@code ДобавитьТест} (Russian for "addTest", the YAXUnit fluent registration
     * call {@code .ДобавитьТест("Имя")}).
     */
    static final String RU_ADD_TEST =
        "\\u0414\\u043E\\u0431\\u0430\\u0432\\u0438\\u0442\\u044C\\u0422\\u0435\\u0441\\u0442"; //$NON-NLS-1$

    /**
     * {@code ДобавитьТестовыйНабор} (Russian for "addTestSet", marks the start of a
     * registered test set) = {@code RU_ADD_TEST} + {@code овыйНабор}.
     */
    static final String RU_ADD_TEST_SET =
        RU_ADD_TEST + "\\u043E\\u0432\\u044B\\u0439\\u041D\\u0430\\u0431\\u043E\\u0440"; //$NON-NLS-1$

    /** Matches {@code ДобавитьТест("Имя")} and captures the test-name literal. */
    private static final Pattern TEST_NAME_PATTERN = Pattern.compile(
        "(?i)" + RU_ADD_TEST + "\\s*\\(\\s*\"([^\"]+)\"\\s*\\)"); //$NON-NLS-1$ //$NON-NLS-2$

    /** Matches a {@code ДобавитьТестовыйНабор(...)} call - the suite registration marker. */
    private static final Pattern TEST_SET_PATTERN = Pattern.compile(
        "(?i)" + RU_ADD_TEST_SET + "\\s*\\("); //$NON-NLS-1$

    /** One test suite: a module plus the names of its registered test methods. */
    public static final class Suite
    {
        /** The common module NAME (e.g. {@code ОМ_OZON_Scabbers}) - what run_yaxunit_tests.modules wants. */
        public final String moduleName;
        public final String modulePath;
        public final String moduleType;
        public final String parentName;
        public final List<String> tests;

        public Suite(String moduleName, String modulePath, String moduleType, String parentName,
            List<String> tests)
        {
            this.moduleName = moduleName;
            this.modulePath = modulePath;
            this.moduleType = moduleType;
            this.parentName = parentName;
            this.tests = tests == null ? new ArrayList<>() : tests;
        }

        public List<String> tests()
        {
            return tests;
        }
    }

    private ListYaxunitTestsSupport()
    {
        // utility class
    }

    /**
     * Whether a BSL module source registers a YAXUnit test set, i.e. contains a
     * {@code ДобавитьТестовыйНабор(...)} call. A module that merely mentions helper
     * methods is not a suite.
     *
     * @param source the module BSL source, or {@code null}
     * @return {@code true} when the source contains a test-set registration
     */
    public static boolean isTestSuiteSource(String source)
    {
        return source != null && TEST_SET_PATTERN.matcher(source).find();
    }

    /**
     * Extracts the test names a module registers, i.e. the string-literal arguments of
     * every {@code ДобавитьТест("...")} call in declaration order, deduplicated.
     * Parameterized variants (a later {@code .СПараметрами(...)}) collapse onto the one
     * test of that {@code ДобавитьТест}, because the runtime expands them.
     *
     * @param source the module BSL source, or {@code null}
     * @return the test names, possibly empty; never {@code null}
     */
    public static List<String> extractTestNames(String source)
    {
        List<String> names = new ArrayList<>();
        if (source == null)
        {
            return names;
        }
        Matcher m = TEST_NAME_PATTERN.matcher(source);
        Set<String> seen = new LinkedHashSet<>();
        while (m.find())
        {
            String name = m.group(1);
            if (name != null && seen.add(name))
            {
                names.add(name);
            }
        }
        return names;
    }

    /**
     * Assembles the {@code list_yaxunit_tests} JSON envelope, deduplicating the test
     * names of each suite while preserving order.
     *
     * @param project the EDT project name
     * @param suites the collected suites
     * @return the serialized JSON envelope
     */
    public static String buildResponse(String project, List<Suite> suites)
    {
        JsonArray suitesArr = new JsonArray();
        int testCount = 0;
        for (Suite suite : suites)
        {
            JsonObject suiteObj = new JsonObject();
            suiteObj.addProperty("moduleName", suite.moduleName); //$NON-NLS-1$
            suiteObj.addProperty("modulePath", suite.modulePath); //$NON-NLS-1$
            suiteObj.addProperty("moduleType", suite.moduleType); //$NON-NLS-1$
            suiteObj.addProperty("parentName", suite.parentName); //$NON-NLS-1$
            JsonArray testsArr = new JsonArray();
            Map<String, Boolean> seen = new LinkedHashMap<>();
            for (String name : suite.tests)
            {
                if (name == null || seen.put(name, Boolean.TRUE) != null)
                {
                    continue;
                }
                JsonObject testObj = new JsonObject();
                testObj.addProperty("name", name); //$NON-NLS-1$
                testsArr.add(testObj);
                testCount++;
            }
            suiteObj.add("tests", testsArr); //$NON-NLS-1$
            suitesArr.add(suiteObj);
        }
        JsonObject root = new JsonObject();
        root.addProperty("project", project); //$NON-NLS-1$
        root.addProperty("count", suitesArr.size()); //$NON-NLS-1$
        root.addProperty("testCount", testCount); //$NON-NLS-1$
        root.add("suites", suitesArr); //$NON-NLS-1$
        return GsonProvider.toJson(root);
    }
}
