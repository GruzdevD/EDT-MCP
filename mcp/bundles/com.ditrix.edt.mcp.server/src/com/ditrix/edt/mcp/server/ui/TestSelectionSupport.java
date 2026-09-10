/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 *
 * edt-mcp-vanessa: SWT-free helpers for the Test-Selection view.
 */

package com.ditrix.edt.mcp.server.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Pure, SWT-free parsing and parameter-building helpers for
 * {@link TestSelectionView}. Keeping these static and free of the UI toolkit makes
 * the wire-contract assumptions (the Markdown shape of {@code list_modules} and the
 * JSON envelopes of the Vanessa tools / background jobs) unit-testable without an
 * Eclipse runtime, mirroring how {@code McpHistoryView} extracts its filter
 * predicate into {@code McpHistoryView.matchesFilters}.
 *
 * <p>The view calls the real MCP tools in-process via
 * {@code McpToolRegistry.getInstance().getTool(&lt;name&gt;).execute(...)} and feeds
 * the returned payloads through these helpers.</p>
 */
public final class TestSelectionSupport
{
    private TestSelectionSupport()
    {
        // utility class
    }

    /** One BSL module row parsed from the {@code list_modules} Markdown table. */
    public static final class ModuleRow
    {
        public final String modulePath;
        public final String moduleType;
        public final String parentName;

        public ModuleRow(String modulePath, String moduleType, String parentName)
        {
            this.modulePath = modulePath;
            this.moduleType = moduleType;
            this.parentName = parentName;
        }
    }

    /** One Vanessa {@code .feature} row parsed from the {@code vanessa_list_features} JSON. */
    public static final class FeatureRow
    {
        public final String path;
        public final String featureName;
        public final String tags;

        public FeatureRow(String path, String featureName, String tags)
        {
            this.path = path;
            this.featureName = featureName;
            this.tags = tags;
        }
    }

    /** One YAxUnit test suite (a module) parsed from the {@code list_yaxunit_tests} JSON. */
    public static final class SuiteRow
    {
        /** The common module NAME - what {@code run_yaxunit_tests}.{@code modules} takes. */
        public final String moduleName;
        public final String modulePath;
        public final String moduleType;
        public final String parentName;
        public final List<String> tests;

        public SuiteRow(String moduleName, String modulePath, String moduleType, String parentName,
            List<String> tests)
        {
            this.moduleName = moduleName;
            this.modulePath = modulePath;
            this.moduleType = moduleType;
            this.parentName = parentName;
            this.tests = tests == null ? List.of() : tests;
        }
    }

    /** One YAxUnit test inside a suite, addressed by its owning module name and its name. */
    public static final class TestRow
    {
        /** The owning common module NAME (not a file path). */
        public final String suiteName;
        public final String name;

        public TestRow(String suiteName, String name)
        {
            this.suiteName = suiteName;
            this.name = name;
        }

        /** The {@code Module.Method} token YAXUnit's {@code tests} filter expects. */
        public String qualifiedName()
        {
            return suiteName + "." + name; //$NON-NLS-1$
        }
    }

    /** Sub-directory of {@code user.home} holding the per-project {@code env.sh} files. */
    private static final String VANESSA_PROJECTS_DIR = ".1c-tools/vanessa/projects"; //$NON-NLS-1$

    // --------------------------------------------------------------------- parse

    /**
     * Parses the {@code list_modules} response (a Markdown table with columns
     * {@code Module Path | Module Type | Parent Type | Parent Name}) into rows.
     *
     * <p>Only the data rows of the table are read: a line is a candidate when it
     * starts with {@code |} and holds at least two {@code |}-separated cells; the
     * header and the {@code | --- |} separator auto-excluded by requiring the first
     * cell to look like a module path (no heading {@code #}, a non-empty path cell,
     * and a cell count that matches a data row). Markdown-escaped pipes in a path
     * ({@code \|}) are unescaped. Non-table prose ("## BSL Modules ...", "**Total:**")
     * is ignored.</p>
     *
     * @param markdown the {@code list_modules} Markdown response, or {@code null}
     * @return the parsed module rows, possibly empty; never {@code null}
     */
    public static List<ModuleRow> parseModules(String markdown)
    {
        List<ModuleRow> rows = new ArrayList<>();
        if (markdown == null)
        {
            return rows;
        }
        for (String line : markdown.split("\\r?\\n")) //$NON-NLS-1$
        {
            String trimmed = line.trim();
            if (!trimmed.startsWith("|")) //$NON-NLS-1$
            {
                continue;
            }
            // splitOnUnescapedPipes strips the leading '|', so the first cell is
            // already the path (Module Path), not a leading empty cell. Columns:
            // Module Path | Module Type | Parent Type | Parent Name.
            String[] cells = splitOnUnescapedPipes(trimmed);
            if (cells.length < 4)
            {
                continue;
            }
            String path = unescape(cells[0]).trim();
            String type = unescape(cells[1]).trim();
            String parent = unescape(cells[3]).trim();
            // A module path always contains '/'; the header ("Module Path") and the
            // separator row ("---") do not, which keeps them out of the data rows.
            if (path.isEmpty() || path.startsWith("#") || isSeparatorRow(path) //$NON-NLS-1$
                || !path.contains("/")) //$NON-NLS-1$
            {
                continue;
            }
            rows.add(new ModuleRow(path, type, parent));
        }
        return rows;
    }

    /**
     * Parses the {@code vanessa_list_features} JSON envelope ({@code features:[{path,
     * featureName, tags}]}) into rows, preserving file order. Any parse failure or a
     * missing {@code features} array yields an empty list (callers surface a generic
     * message rather than throwing).
     *
     * @param json the {@code vanessa_list_features} response, or {@code null}
     * @return the parsed feature rows, possibly empty; never {@code null}
     */
    public static List<FeatureRow> parseFeatures(String json)
    {
        List<FeatureRow> rows = new ArrayList<>();
        if (json == null || json.isBlank())
        {
            return rows;
        }
        try
        {
            JsonElement root = JsonParser.parseString(json);
            if (!root.isJsonObject())
            {
                return rows;
            }
            JsonElement features = root.getAsJsonObject().get("features"); //$NON-NLS-1$
            if (features == null || !features.isJsonArray())
            {
                return rows;
            }
            for (JsonElement el : features.getAsJsonArray())
            {
                if (!el.isJsonObject())
                {
                    continue;
                }
                JsonObject obj = el.getAsJsonObject();
                String path = stringOr(obj.get("path"), ""); //$NON-NLS-1$ //$NON-NLS-2$
                String name = stringOr(obj.get("featureName"), ""); //$NON-NLS-1$ //$NON-NLS-2$
                String tags = stringOr(obj.get("tags"), ""); //$NON-NLS-1$ //$NON-NLS-2$
                if (path.isEmpty())
                {
                    continue;
                }
                rows.add(new FeatureRow(path, name, tags));
            }
        }
        catch (RuntimeException e) // NOSONAR a malformed response yields empty rows, never thrown
        {
            // ignore
        }
        return rows;
    }

    /**
     * Parses the {@code list_yaxunit_tests} JSON envelope
     * ({@code suites:[{moduleName, modulePath, moduleType, parentName, tests:[{name}]}]})
     * into rows, preserving file order. Any parse failure or a missing {@code suites}
     * array yields an empty list (callers surface a generic message rather than
     * throwing).
     *
     * @param json the {@code list_yaxunit_tests} response, or {@code null}
     * @return the parsed suite rows, possibly empty; never {@code null}
     */
    public static List<SuiteRow> parseYaxunitSuites(String json)
    {
        List<SuiteRow> rows = new ArrayList<>();
        if (json == null || json.isBlank())
        {
            return rows;
        }
        try
        {
            JsonElement root = JsonParser.parseString(json);
            if (!root.isJsonObject())
            {
                return rows;
            }
            JsonElement suites = root.getAsJsonObject().get("suites"); //$NON-NLS-1$
            if (suites == null || !suites.isJsonArray())
            {
                return rows;
            }
            for (JsonElement el : suites.getAsJsonArray())
            {
                if (!el.isJsonObject())
                {
                    continue;
                }
                JsonObject obj = el.getAsJsonObject();
                String name = stringOr(obj.get("moduleName"), ""); //$NON-NLS-1$ //$NON-NLS-2$
                String path = stringOr(obj.get("modulePath"), ""); //$NON-NLS-1$ //$NON-NLS-2$
                if (name.isEmpty())
                {
                    continue;
                }
                List<String> tests = new ArrayList<>();
                JsonElement testsEl = obj.get("tests"); //$NON-NLS-1$
                if (testsEl != null && testsEl.isJsonArray())
                {
                    for (JsonElement t : testsEl.getAsJsonArray())
                    {
                        if (!t.isJsonObject())
                        {
                            continue;
                        }
                        String testName = stringOr(t.getAsJsonObject().get("name"), ""); //$NON-NLS-1$ //$NON-NLS-2$
                        if (!testName.isEmpty())
                        {
                            tests.add(testName);
                        }
                    }
                }
                rows.add(new SuiteRow(name, path,
                    stringOr(obj.get("moduleType"), ""), //$NON-NLS-1$ //$NON-NLS-2$
                    stringOr(obj.get("parentName"), ""), tests)); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }
        catch (RuntimeException e) // NOSONAR a malformed response yields empty rows, never thrown
        {
            // ignore
        }
        return rows;
    }

    // --------------------------------------------------------- parameter build

    /**
     * Builds the parameter map for {@code run_yaxunit_tests} from the view's shared
     * context (the launch configuration name or an explicit project/application) plus
     * the selected module names. YAXUnit filters modules {@code modules} by the common
     * module NAME (e.g. {@code ОМ_OZON_Scabbers}), never by a BSL file path. The base
     * map's context keys are preserved and only {@code modules} and a bounded
     * {@code timeout} are added, so the caller controls which identification strategy
     * (launch config vs. project+applicationId) is used.
     *
     * @param base  the shared context params (may be empty, never {@code null})
     * @param modules the selected module names (comma-joined); may be empty
     * @return a new parameter map for {@code run_yaxunit_tests}
     */
    public static Map<String, String> buildYaxunitParams(Map<String, String> base, List<String> modules)
    {
        Map<String, String> params = new LinkedHashMap<>(base);
        params.put("modules", String.join(",", modules)); //$NON-NLS-1$
        params.put("timeout", "45"); //$NON-NLS-1$
        return params;
    }

    /**
     * Builds the parameter map for {@code run_yaxunit_tests} from the shared context and the
     * checked tree selection, using exactly ONE filter family per run because YAXUnit
     * ANDs {@code modules} and {@code tests}:
     * <ul>
     *   <li>only suites checked → {@code modules} = their module names;</li>
     *   <li>only tests checked → {@code tests} = their {@code Module.Method} tokens;</li>
     *   <li>both → {@code tests} = every test of each checked suite (enumerated) union the
     *       explicitly checked tests, the tests check-boxed under a checked suite being
     *       already covered.</li>
     * </ul>
     * The base map's context keys (e.g. {@code launchConfigurationName}) are preserved and only
     * the filter family is added.
     *
     * @param base the shared context params (may be empty, never {@code null})
     * @param checkedSuites the checked suite rows (may be empty)
     * @param checkedTests the checked test rows (may be empty)
     * @return a new parameter map for {@code run_yaxunit_tests}
     */
    public static Map<String, String> buildYaxunitRunParams(Map<String, String> base,
        List<SuiteRow> checkedSuites, List<TestRow> checkedTests)
    {
        Map<String, String> params = new LinkedHashMap<>(base);
        if (checkedSuites.isEmpty())
        {
            List<String> tests = new ArrayList<>();
            for (TestRow t : checkedTests)
            {
                tests.add(t.qualifiedName());
            }
            params.put("tests", String.join(",", tests)); //$NON-NLS-1$
            return params;
        }
        if (checkedTests.isEmpty())
        {
            List<String> modules = new ArrayList<>();
            for (SuiteRow s : checkedSuites)
            {
                modules.add(s.moduleName);
            }
            params.put("modules", String.join(",", modules)); //$NON-NLS-1$
            return params;
        }
        LinkedHashSet<String> tests = new LinkedHashSet<>();
        for (SuiteRow s : checkedSuites)
        {
            for (String name : s.tests)
            {
                tests.add(s.moduleName + "." + name); //$NON-NLS-1$
            }
        }
        for (TestRow t : checkedTests)
        {
            tests.add(t.qualifiedName());
        }
        params.put("tests", String.join(",", tests)); //$NON-NLS-1$
        return params;
    }

    /**
     * Builds the parameter map for {@code vanessa_run_feature} from the shared context
     * plus the selected {@code .feature} path.
     *
     * @param base  the shared context params (may be empty, never {@code null})
     * @param featurePath the absolute {@code .feature} path to run
     * @return a new parameter map for {@code vanessa_run_feature}
     */
    public static Map<String, String> buildBddRunParams(Map<String, String> base, String featurePath)
    {
        Map<String, String> params = new LinkedHashMap<>(base);
        params.put("feature", featurePath); //$NON-NLS-1$
        return params;
    }

    // ----------------------------------------------------- context enumeration

    /**
     * Enumerates the configured Vanessa Automation project keys by listing the
     * {@code ~/.1c-tools/vanessa/projects} directory (each sub-directory that has an
     * {@code env.sh} is a runnable project, mirroring how the Vanessa tools resolve a
     * project). The keys are returned sorted; an absent/unreadable directory yields an
     * empty list so the view degrades to a plain editable field rather than crashing.
     *
     * @return the sorted project keys, possibly empty; never {@code null}
     */
    public static List<String> listVanessaProjects()
    {
        List<String> names = new ArrayList<>();
        String home = System.getProperty("user.home"); //$NON-NLS-1$
        if (home == null || home.isEmpty())
        {
            return names;
        }
        Path dir = Paths.get(home, VANESSA_PROJECTS_DIR);
        if (!Files.isDirectory(dir))
        {
            return names;
        }
        try (var stream = Files.list(dir))
        {
            stream.filter(Files::isDirectory)
                .map(p -> p.getFileName().toString())
                .filter(n -> !n.isEmpty())
                .sorted()
                .forEach(names::add);
        }
        catch (IOException e) // NOSONAR an unreadable dir yields the empty list, never thrown
        {
            return new ArrayList<>();
        }
        return names;
    }

    /**
     * Parses the {@code list_configurations} JSON envelope
     * ({@code configurations:[{name,...}], count}) into the config names, preserving
     * document order. The caller may pre-filter server-side via {@code projectName} /
     * {@code type}. Malformed input yields an empty list.
     *
     * @param json the {@code list_configurations} response, or {@code null}
     * @return the configuration names, possibly empty; never {@code null}
     */
    public static List<String> parseConfigNames(String json)
    {
        List<String> names = new ArrayList<>();
        if (json == null || json.isBlank())
        {
            return names;
        }
        try
        {
            JsonElement root = JsonParser.parseString(json);
            if (!root.isJsonObject())
            {
                return names;
            }
            JsonElement configs = root.getAsJsonObject().get("configurations"); //$NON-NLS-1$
            if (configs == null || !configs.isJsonArray())
            {
                return names;
            }
            for (JsonElement el : configs.getAsJsonArray())
            {
                if (!el.isJsonObject())
                {
                    continue;
                }
                String name = stringOr(el.getAsJsonObject().get("name"), ""); //$NON-NLS-1$ //$NON-NLS-2$
                if (!name.isEmpty())
                {
                    names.add(name);
                }
            }
        }
        catch (RuntimeException e) // NOSONAR a malformed response yields empty rows, never thrown
        {
            // ignore
        }
        return names;
    }

    // ------------------------------------------------------- pending/job parsing

    /** Matches the background job id in the YAXUnit {@code **Pending:**} Markdown. */
    private static final Pattern JOB_ID_PATTERN =
        Pattern.compile("background job `([^`]+)`"); //$NON-NLS-1$

    /**
     * Matches the job status in the {@code get_job_status} Markdown render: either the
     * leading heading {@code # Background job: <status>} or a {@code | status | <status> |}
     * summary row.
     */
    private static final Pattern JOB_STATUS_PATTERN =
        Pattern.compile("(?:# Background job: |\\|\\s*status\\s*\\|\\s*)([a-zA-Z]+)"); //$NON-NLS-1$

    /**
     * Extracts the background job id from a {@code run_yaxunit_tests} {@code Pending}
     * style return, or {@code null} when the text does not carry one (i.e. the call
     * returned a finished report, not a pending job).
     *
     * @param result the raw tool return, or {@code null}
     * @return the job id, or {@code null}
     */
    public static String extractJobId(String result)
    {
        if (result == null)
        {
            return null;
        }
        Matcher m = JOB_ID_PATTERN.matcher(result);
        return m.find() ? m.group(1) : null;
    }

    /**
     * Extracts the job state from a {@code get_job_status} Markdown render, one of
     * {@code running}/{@code done}/{@code failed}/{@code cancelled} (the
     * {@link #JOB_STATUS_PATTERN} word directly after the status marker), or {@code null}
     * when the render does not carry a recognizable status. Used by the view to decide
     * when a YAXUnit background job has reached a terminal state.
     *
     * @param render the {@code get_job_status} Markdown render, or {@code null}
     * @return the status value, or {@code null}
     */
    public static String extractJobStatus(String render)
    {
        if (render == null)
        {
            return null;
        }
        Matcher m = JOB_STATUS_PATTERN.matcher(render);
        return m.find() ? m.group(1) : null;
    }

    /**
     * Returns the {@code error} message of a tool {@code ToolResult.error(...).toJson()}
     * envelope, or {@code null} when the text is not such an error envelope (a valid
     * payload, an empty body, or unparseable input). Lets the view distinguish a tool
     * failure — which it must surface — from a valid-but-empty result.
     *
     * @param json the raw tool return, or {@code null}
     * @return the error text, or {@code null}
     */
    public static String extractToolError(String json)
    {
        if (json == null || json.isBlank())
        {
            return null;
        }
        try
        {
            JsonElement root = JsonParser.parseString(json);
            if (!root.isJsonObject())
            {
                return null;
            }
            JsonElement error = root.getAsJsonObject().get("error"); //$NON-NLS-1$
            if (error == null || !error.isJsonPrimitive() || !error.getAsJsonPrimitive().isString())
            {
                return null;
            }
            return error.getAsString();
        }
        catch (RuntimeException e) // NOSONAR a malformed response yields null, never thrown
        {
            return null;
        }
    }

    /**
     * Extracts the numeric {@code launchId} from the {@code vanessa_run_feature} JSON
     * envelope (a number that may arrive as a JSON integer; the id is re-serialized as
     * a plain integer string, never {@code "1.0"}).
     *
     * @param json the {@code vanessa_run_feature} response, or {@code null}
     * @return the launch id as a string, or {@code null} when absent/unparseable
     */
    public static String extractLaunchId(String json)
    {
        if (json == null || json.isBlank())
        {
            return null;
        }
        try
        {
            JsonElement root = JsonParser.parseString(json);
            if (!root.isJsonObject())
            {
                return null;
            }
            JsonElement id = root.getAsJsonObject().get("launchId"); //$NON-NLS-1$
            return id == null || !id.isJsonPrimitive() ? null : id.getAsJsonPrimitive().getAsString();
        }
        catch (RuntimeException e) // NOSONAR a malformed response yields null, never thrown
        {
            return null;
        }
    }

    /**
     * Extracts the {@code status} field from a {@code vanessa_get_execution_status}
     * JSON envelope (lowercased by the tool), or {@code null} when absent.
     *
     * @param json the status response, or {@code null}
     * @return the status value, or {@code null}
     */
    public static String extractExecutionStatus(String json)
    {
        if (json == null || json.isBlank())
        {
            return null;
        }
        try
        {
            JsonElement root = JsonParser.parseString(json);
            if (!root.isJsonObject())
            {
                return null;
            }
            JsonElement status = root.getAsJsonObject().get("status"); //$NON-NLS-1$
            return status == null || !status.isJsonPrimitive() ? null : status.getAsString();
        }
        catch (RuntimeException e) // NOSONAR a malformed response yields null, never thrown
        {
            return null;
        }
    }

    /**
     * Extracts the Markdown report body from a {@code vanessa_get_test_report} JSON
     * envelope ({@code report}), or {@code null} when absent.
     *
     * @param json the report response, or {@code null}
     * @return the report text, or {@code null}
     */
    public static String extractReport(String json)
    {
        if (json == null || json.isBlank())
        {
            return null;
        }
        try
        {
            JsonElement root = JsonParser.parseString(json);
            if (!root.isJsonObject())
            {
                return null;
            }
            JsonElement report = root.getAsJsonObject().get("report"); //$NON-NLS-1$
            return report == null || report.isJsonNull() ? null : report.getAsString();
        }
        catch (RuntimeException e) // NOSONAR a malformed response yields null, never thrown
        {
            return null;
        }
    }

    // ------------------------------------------------------------------ plumbing

    /** Splits a table line on unescaped ({@code \|}) pipe separators, keeping cell count. */
    private static String[] splitOnUnescapedPipes(String line)
    {
        // Strip the leading '|' and split on '|' outside of '\|' escapes.
        String body = line.startsWith("|") ? line.substring(1) : line; //$NON-NLS-1$
        List<String> cells = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean escaped = false;
        for (int i = 0; i < body.length(); i++)
        {
            char c = body.charAt(i);
            if (c == '\\' && !escaped)
            {
                escaped = true;
                cur.append(c);
                continue;
            }
            if (c == '|' && !escaped)
            {
                cells.add(cur.toString());
                cur.setLength(0);
                continue;
            }
            escaped = false;
            cur.append(c);
        }
        cells.add(cur.toString());
        return cells.toArray(new String[0]);
    }

    private static String unescape(String cell)
    {
        return cell.replace("\\|", "|"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** A Markdown separator row has only dashes ({@code ---}) in its type/path columns. */
    private static boolean isSeparatorRow(String cell)
    {
        return !cell.isEmpty() && cell.matches("-+"); //$NON-NLS-1$
    }

    private static String stringOr(JsonElement el, String fallback)
    {
        if (el == null || !el.isJsonPrimitive() || !el.getAsJsonPrimitive().isString())
        {
            return fallback;
        }
        String v = el.getAsString();
        return v == null ? fallback : v;
    }
}
