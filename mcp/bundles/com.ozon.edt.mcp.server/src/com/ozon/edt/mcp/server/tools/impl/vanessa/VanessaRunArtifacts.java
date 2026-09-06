/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 *
 * edt-mcp-vanessa: generate the artifacts a Vanessa Automation BDD run consumes.
 */

package com.ozon.edt.mcp.server.tools.impl.vanessa;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import com.ozon.edt.mcp.server.protocol.GsonProvider;

/**
 * Reproduces the VAParams override that {@code run-edt.sh} builds before it
 * launches the client, so a BDD run can be started directly from an MCP tool:
 * an <em>override</em> VAParams json derived from the project's base
 * VAParams.json, with absolute paths for features, logs, status and the
 * JUnit/Allure output baked in. The client is then launched with
 * {@code /Execute vanessa-automation.epf /C"StartFeaturePlayer;VAParams=<override>"}
 * — see {@link #VA_COMMAND}.
 *
 * <p>Pure file work based on the project config — no EDT model access, no
 * transactions. Keys and layout mirror the Python in {@code run-edt.sh} so the
 * two paths stay interchangeable.
 */
public final class VanessaRunArtifacts
{
    /** Per-run artifact sub-folder under {@code va/<project>}. */
    private static final String RUN_DIR = "run"; //$NON-NLS-1$

    /** Terminal VA status/log file names (absolute, under the out dir). */
    static final String LOG_FILE = "BDD.log"; //$NON-NLS-1$
    static final String STATUS_FILE = "BDDStatus.log"; //$NON-NLS-1$
    static final String JUNIT_DIR = "junit"; //$NON-NLS-1$
    static final String JUNIT_FILE = "junit.xml"; //$NON-NLS-1$
    static final String ALLURE_DIR = "allure"; //$NON-NLS-1$

    /** Vanessa /C startup option prefix consumed by vanessa-automation.epf. */
    public static final String VA_COMMAND = "StartFeaturePlayer;VAParams="; //$NON-NLS-1$

    private VanessaRunArtifacts()
    {
        // Utility class
    }

    /**
     * Result of {@link #generate(VanessaProjectConfig, String)}.
     */
    public static final class Result
    {
        /** Absolute path to the VAParams override json written for this run. */
        public final String vaparamsOverride;
        /** Absolute out directory (logs, junit, allure live here). */
        public final String outDir;

        Result(String vaparamsOverride, String outDir)
        {
            this.vaparamsOverride = vaparamsOverride;
            this.outDir = outDir;
        }
    }

    /**
     * Generates a complete run artifact set for a feature target with the default options.
     *
     * @param config project configuration
     * @param featureTarget absolute path to a feature file or feature directory
     * @return the generated artifact paths, plus the out dir
     * @throws IOException if a VAParams read or an artifact write fails
     */
    public static Result generate(VanessaProjectConfig config, String featureTarget) throws IOException
    {
        return generate(config, featureTarget, GenerateOptions.EMPTY);
    }

    /**
     * Generates a complete run artifact set for a feature target.
     *
     * @param config project configuration
     * @param featureTarget absolute path to a feature file or feature directory
     * @param options per-run overrides (tags, scenarios, retries, screenshots, allure, report dir)
     * @return the generated artifact paths, plus the out dir
     * @throws IOException if a VAParams read or an artifact write fails
     */
    public static Result generate(VanessaProjectConfig config, String featureTarget,
        GenerateOptions options) throws IOException
    {
        Path vaDir = config.vaDir();
        Path outDir = options != null && options.reportDir != null && !options.reportDir.trim().isEmpty()
            ? Paths.get(expand(options.reportDir))
            : config.outDir();
        Files.createDirectories(vaDir);
        Files.createDirectories(outDir.resolve(JUNIT_DIR));
        Files.createDirectories(outDir.resolve(ALLURE_DIR));

        String target = expand(featureTarget);
        String override = writeOverride(config, target, outDir, vaDir, options);
        return new Result(override, outDir.toString());
    }

    /**
     * Optional per-run overrides that shape the VAParams override beyond the
     * defaults {@code run-edt.sh} bakes in. Null/absent fields leave the base
     * VAParams untouched. Field names mirror the tool parameters and the VAParams
     * keys are applied in {@link #applyOptions}.
     */
    public static final class GenerateOptions
    {
        /** Reuse for the common case of "no overrides". */
        public static final GenerateOptions EMPTY = new GenerateOptions();

        /** {@code СписокТеговОтбор}: run only scenarios/features carrying these tags. */
        public String tagsFilter;
        /** {@code СписокТеговИсключение}: skip scenarios/features carrying these tags. */
        public String tagsIgnore;
        /** {@code СписокСценариевДляВыполнения}: run only these scenario names. */
        public String scenarios;
        /** {@code КоличествоПопытокВыполненияСценария}: retries on a failed scenario (&gt;1). */
        public Integer retries;
        /** {@code ДелатьСкриншотПриВозникновенииОшибки}. */
        public Boolean screenshotsOnError;
        /** {@code КаталогВыгрузкиСкриншотов}, when screenshots are enabled. */
        public String screenshotsDir;
        /** {@code ВыполнятьШагиАсинхронно}. */
        public Boolean asyncSteps;
        /** {@code ДелатьОтчетВФорматеАллюр}. */
        public Boolean allure;
        /** When set, replaces the project out dir (logs/junit/allure live here). */
        public String reportDir;

        /** True when no option is set — skips the override pass entirely. */
        public boolean isEmpty()
        {
            return tagsFilter == null && tagsIgnore == null && scenarios == null
                && retries == null && screenshotsOnError == null && screenshotsDir == null
                && asyncSteps == null && allure == null && reportDir == null;
        }
    }

    private static String writeOverride(VanessaProjectConfig config, String featureTarget,
        Path outDir, Path vaDir, GenerateOptions options) throws IOException
    {
        JsonObject params = readBaseVAParams(config.vaparams);

        params.addProperty("КаталогФич", featureTarget); //$NON-NLS-1$
        params.addProperty("КаталогПроекта", featureTarget); //$NON-NLS-1$
        params.addProperty("КаталогЛогов", outDir.toString()); //$NON-NLS-1$
        params.addProperty("ИмяФайлаЛогВыполненияСценариев", //$NON-NLS-1$
            outDir.resolve(LOG_FILE).toString());
        params.addProperty("ПутьКФайлуДляВыгрузкиСтатусаВыполненияСценариев", //$NON-NLS-1$
            outDir.resolve(STATUS_FILE).toString());

        JsonObject junit = params.getAsJsonObject("ОтчетJUnit"); //$NON-NLS-1$
        if (junit != null)
        {
            junit.addProperty("КаталогВыгрузкиJUnit", outDir.resolve(JUNIT_DIR).toString()); //$NON-NLS-1$
        }
        JsonElement allureEl = params.get("ОтчетAllure"); //$NON-NLS-1$
        if (allureEl != null && allureEl.isJsonObject())
        {
            allureEl.getAsJsonObject()
                .addProperty("КаталогВыгрузкиAllure", outDir.resolve(ALLURE_DIR).toString()); //$NON-NLS-1$
        }

        params.addProperty("ИспользоватьКомпонентуVanessaExt", false); //$NON-NLS-1$
        params.addProperty("ИспользоватьПарсерGherkinИзКомпонентыVanessaExt", false); //$NON-NLS-1$
        params.addProperty("ПоискКартинокСПомощьюКомпонентыVanessaExt", false); //$NON-NLS-1$
        params.addProperty("ИспользоватьUIAutomation", false); //$NON-NLS-1$
        params.addProperty("ИспользоватьВнешнююКомпонентуДляСкриншотов", false); //$NON-NLS-1$
        params.addProperty("СпособСнятияСкриншотовВнешнейКомпонентой", 0); //$NON-NLS-1$
        params.addProperty("КомандаСделатьСкриншот", "/usr/sbin/screencapture -x "); //$NON-NLS-1$

        JsonArray libs = filterLibraries(params.getAsJsonArray("КаталогиБиблиотек"), //$NON-NLS-1$
            featureTarget, config.vaLibs);
        params.add("КаталогиБиблиотек", libs); //$NON-NLS-1$
        configureTestClient(params, config);
        applyOptions(params, options, outDir);

        Path override = vaDir.resolve(RUN_DIR).resolve("run.VAParams.json"); //$NON-NLS-1$
        writeJson(override, params);
        return override.toString();
    }

    private static void applyOptions(JsonObject params, GenerateOptions options, Path outDir)
    {
        if (options == null)
        {
            return;
        }
        if (options.tagsFilter != null)
        {
            params.addProperty("СписокТеговОтбор", options.tagsFilter); //$NON-NLS-1$
        }
        if (options.tagsIgnore != null)
        {
            params.addProperty("СписокТеговИсключение", options.tagsIgnore); //$NON-NLS-1$
        }
        if (options.scenarios != null)
        {
            params.addProperty("СписокСценариевДляВыполнения", options.scenarios); //$NON-NLS-1$
        }
        if (options.retries != null)
        {
            params.addProperty("КоличествоПопытокВыполненияСценария", options.retries); //$NON-NLS-1$
        }
        if (options.screenshotsOnError != null)
        {
            params.addProperty("ДелатьСкриншотПриВозникновенииОшибки", options.screenshotsOnError); //$NON-NLS-1$
        }
        if (options.screenshotsDir != null)
        {
            params.addProperty("КаталогВыгрузкиСкриншотов",
                outDir.resolve(expand(options.screenshotsDir)).toString()); //$NON-NLS-1$
        }
        if (options.asyncSteps != null)
        {
            params.addProperty("ВыполнятьШагиАсинхронно", options.asyncSteps); //$NON-NLS-1$
        }
        if (options.allure != null)
        {
            params.addProperty("ДелатьОтчетВФорматеАллюр", options.allure); //$NON-NLS-1$
        }
    }

    private static JsonObject readBaseVAParams(String vaparamsPath) throws IOException
    {
        if (vaparamsPath == null || vaparamsPath.isEmpty())
        {
            throw new IOException("Project env.sh has no VAPARAMS path configured"); //$NON-NLS-1$
        }
        Path p = Paths.get(vaparamsPath);
        String text;
        try
        {
            text = Files.readString(p, StandardCharsets.UTF_8);
        }
        catch (IOException e)
        {
            throw new IOException("Cannot read base VAParams.json at " + vaparamsPath + ": " + e.getMessage()); //$NON-NLS-1$
        }
        JsonElement el = JsonParser.parseString(text);
        if (el == null || !el.isJsonObject())
        {
            throw new IOException("Base VAParams.json at " + vaparamsPath + " is not a JSON object"); //$NON-NLS-1$
        }
        return el.getAsJsonObject();
    }

    private static JsonArray filterLibraries(JsonArray libs, String featureTarget, String vaLibs)
    {
        JsonArray out = new JsonArray();
        if (libs != null)
        {
            Path base = Paths.get(featureTarget);
            for (JsonElement e : libs)
            {
                if (e == null || !e.isJsonPrimitive())
                {
                    continue;
                }
                String cand = e.getAsString();
                Path resolved = cand.startsWith("/") //$NON-NLS-1$
                    ? Paths.get(cand)
                    : base.resolve(cand);
                if (Files.isDirectory(resolved))
                {
                    out.add(Paths.get(resolved.toString()).normalize().toString());
                }
            }
        }
        if (vaLibs != null && !vaLibs.isEmpty())
        {
            Path abs = Paths.get(vaLibs);
            if (!contains(out, abs.normalize().toString()) && Files.isDirectory(abs))
            {
                out.add(abs.normalize().toString());
            }
        }
        return out;
    }

    private static boolean contains(JsonArray arr, String value)
    {
        for (JsonElement e : arr)
        {
            if (e != null && e.isJsonPrimitive() && value.equals(e.getAsString()))
            {
                return true;
            }
        }
        return false;
    }

    /**
     * Declares the testing client (TestClient) VA drives to execute the scenario
     * steps. Without a defined test client Vanessa cannot run even non-UI steps —
     * it dies on the first step with "Тип не определен (ТестируемаяГруппаФормы)".
     * So every run gets one thin client derived from the project env.sh
     * ({@link VanessaProjectConfig#ibBase} / {@link VanessaProjectConfig#dbUser} /
     * {@link VanessaProjectConfig#dbPwd}) injected into the
     * {@code КлиентТестирования.ДанныеКлиентовТестирования} block of base VAParams.
     *
     * <p>Field names follow Vanessa Automation's own docs/training
     * ({@code docs/JsonParams/JsonParamsRU.md}, {@code CommandSetting.md}): a client
     * record is {Имя, Синоним, ТипКлиента, ПутьКИнфобазе, ДопПараметры, ИмяКомпьютера,
     * ПортЗапускаТестКлиента}. Credentials go in {@code ДопПараметры} as
     * {@code /N"..." /P"..."} (the platform passes them to the spawned test-client
     * session); {@code ИмяКомпьютера}=localhost runs the client on the manager's own
     * machine; {@code ПортЗапускаТестКлиента}=1 lets VA auto-select a free port.</p>
     */
    private static void configureTestClient(JsonObject params, VanessaProjectConfig config)
    {
        JsonObject kt = params.getAsJsonObject("КлиентТестирования"); //$NON-NLS-1$
        if (kt == null)
        {
            kt = new JsonObject();
            params.add("КлиентТестирования", kt); //$NON-NLS-1$
        }
        JsonArray clients = new JsonArray();
        JsonObject client = new JsonObject();
        client.addProperty("Имя", "TClient"); //$NON-NLS-1$ //$NON-NLS-2$
        client.addProperty("Синоним", "Test thin client"); //$NON-NLS-1$
        client.addProperty("ТипКлиента", "Тонкий"); //$NON-NLS-1$
        if (config.ibBase != null && !config.ibBase.isEmpty())
        {
            client.addProperty("ПутьКИнфобазе", //$NON-NLS-1$
                "File=\"" + config.ibBase + "\";"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        StringBuilder extra = new StringBuilder();
        if (config.dbUser != null && !config.dbUser.isEmpty())
        {
            extra.append("/N\"").append(config.dbUser).append("\" "); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
        if (config.dbPwd != null && !config.dbPwd.isEmpty())
        {
            extra.append("/P\"").append(config.dbPwd).append("\""); //$NON-NLS-1$ //$NON-NLS-2$
        }
        client.addProperty("ДопПараметры", extra.toString().trim()); //$NON-NLS-1$
        client.addProperty("ИмяКомпьютера", "localhost"); //$NON-NLS-1$ //$NON-NLS-2$
        client.addProperty("ПортЗапускаТестКлиента", 1); //$NON-NLS-1$
        clients.add(client);
        kt.add("ДанныеКлиентовТестирования", clients); //$NON-NLS-1$
    }

    private static void writeJson(Path path, JsonObject obj) throws IOException
    {
        String json = GsonProvider.get().toJson(obj);
        Path parent = path.getParent();
        if (parent != null)
        {
            Files.createDirectories(parent);
        }
        Files.writeString(path, json, StandardCharsets.UTF_8);
    }

    private static String expand(String value)
    {
        if (value == null)
        {
            return ""; //$NON-NLS-1$
        }
        return value.replace("$HOME", System.getProperty("user.home")); //$NON-NLS-1$ //$NON-NLS-2$
    }
}
