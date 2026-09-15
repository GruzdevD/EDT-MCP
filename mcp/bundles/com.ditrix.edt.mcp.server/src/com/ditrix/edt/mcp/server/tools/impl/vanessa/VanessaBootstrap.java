/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 *
 * edt-mcp-vanessa: turnkey Vanessa Automation provisioning ("Package A").
 */

package com.ditrix.edt.mcp.server.tools.impl.vanessa;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.CoreException;
import org.osgi.framework.FrameworkUtil;

import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.utils.BackgroundJobs;
import com.ditrix.edt.mcp.server.utils.ProjectContext;

/**
 * Turnkey installation of the Vanessa Automation runtime for an EDT project
 * ("Package A", soft scheme): on demand, per project, it creates
 * {@code <projectRoot>/.vanessa/} (templates for {@code env.sh} /
 * {@code VAParams.json} plus {@code features/} and {@code out/}), and the heavy
 * runtime — the {@code vanessa-automation.epf} executable data processor — is
 * downloaded lazily into a shared cache only when VA is first used.
 *
 * <p>The layout is NOT created at plugin activation for every open project — that
 * would drop {@code .vanessa/} into repositories that never use Vanessa. It is
 * provisioned per project only when {@code vanessa_setup} runs for that project
 * (and the run tools report the missing layout with a pointer to it).</p>
 *
 * <p>VA runs are driven by EDT ({@code LaunchTool} + the {@code VA_Runner}
 * external processor), so the plugin does NOT need OneScript/vrunner: the only
 * external binaries involved are the VA {@code .epf} (plus its {@code locales/})
 * and, optionally, the Allure CLI for {@code vanessa_open_allure_report}.</p>
 *
 * <p><b>Layout.</b> Per-project (in the EDT project root, provisioned):
 * {@code .vanessa/env.sh}, {@code .vanessa/VAParams.json},
 * {@code .vanessa/features/}, {@code .vanessa/out/}<project>,
 * {@code .vanessa/va/}<project>. Shared cache (per-user):
 * {@code ~/.1c-tools/vanessa/va/<ver>/} (the downloaded epf + locales) and
 * {@code ~/.1c-tools/allure/} (the Allure CLI, matching
 * {@link AllureReportService}). The legacy {@code ~/.1c-tools/vanessa/projects/}
 * layout stays a read fallback (see {@link VanessaProjectConfig}).</p>
 *
 * <p>The layout/provision/doctor logic is pure — it takes paths, not an EDT
 * workspace — so it is unit-testable without EDT; only the background-job
 * wrappers and the network download touch the platform.</p>
 *
 * <p>The pinned VA release is the public, actively maintained publishing repo for
 * the {@code vanessa-automation.epf} (GitHub: {@code Pr-Mex/vanessa-automation}); the zip holds
 * the epf next to its {@code locales/}, exactly the layout our local run recipe needs.
 * The Allure CLI pins {@code allure-framework/allure2}.</p>
 */
public final class VanessaBootstrap
{
    /** Sub-directory inside the project root holding the provisioned VA tree. */
    static final String VANESSA_DIR = ".vanessa"; //$NON-NLS-1$

    /** Relative names under {@value #VANESSA_DIR}. */
    static final String DIR_ENV = "env.sh"; //$NON-NLS-1$
    static final String DIR_VAPARAMS = "VAParams.json"; //$NON-NLS-1$
    static final String DIR_FEATURES = "features"; //$NON-NLS-1$
    static final String DIR_OUT = "out"; //$NON-NLS-1$
    static final String DIR_VA = "va"; //$NON-NLS-1$

    /** Shared runtime cache root (per-user, not in the project tree). */
    static final String VANESSA_WS = ".1c-tools/vanessa"; //$NON-NLS-1$
    /** Allure CLI cache root (shared with {@link AllureReportService}). */
    static final String ALLURE_WS = ".1c-tools/allure"; //$NON-NLS-1$

    /** Public GitHub repo the VA epf is published from. */
    static final String VA_REPO = "Pr-Mex/vanessa-automation"; //$NON-NLS-1$
    /** Pinned VA release tag. Bump to re-baseline the cached runtime. */
    static final String VA_TAG = "1.2.043.42"; //$NON-NLS-1$
    /** Release asset containing {@code vanessa-automation.epf} + {@code locales/}. */
    static final String VA_ZIP = "vanessa-automation." + VA_TAG + ".zip"; //$NON-NLS-1$
    /** The extracted epf file name inside the cache dir. */
    static final String EPF_FILE = "vanessa-automation.epf"; //$NON-NLS-1$
    /** Top-level folder inside the release zip holding the runtime tree. */
    static final String RELEASE_SUBDIR = "vanessa-automation"; //$NON-NLS-1$
    /** Runtime sub-folders of the release that make up the VA project's {@code bin/}. */
    static final String[] BIN_DIRS = { "features", "lib", "plugins", "locales", "vendor" }; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$

    /** Public GitHub repo the Allure CLI is published from. */
    static final String ALLURE_REPO = "allure-framework/allure2"; //$NON-NLS-1$
    /** Pinned Allure CLI release tag. */
    static final String ALLURE_TAG = "2.46.1"; //$NON-NLS-1$
    /** Release asset (tar.gz) of the standalone Allure distribution. */
    static final String ALLURE_TGZ = "allure-" + ALLURE_TAG + ".tgz"; //$NON-NLS-1$

    /** Template resource folder (relative to the bundle root). */
    private static final String TEMPLATES_DIR = "templates/"; //$NON-NLS-1$

    private VanessaBootstrap()
    {
        // Utility class
    }

    // ---------------------------------------------------------------------
    // Shared-cache paths (per-user, versioned so re-baselining is additive)
    // ---------------------------------------------------------------------

    /** {@code ~/.1c-tools/vanessa/va/<ver>/} — the downloaded VA runtime. */
    public static Path epfCacheDir()
    {
        return Paths.get(System.getProperty("user.home"), //$NON-NLS-1$
            VANESSA_WS, DIR_VA, VA_TAG);
    }

    /** {@code ~/.1c-tools/vanessa/va/<ver>/vanessa-automation.epf}. */
    public static Path epfCacheFile()
    {
        return epfCacheDir().resolve(EPF_FILE);
    }

    /** {@code ~/.1c-tools/allure/} — holds {@code allure-<ver>} installs. */
    public static Path allureCacheDir()
    {
        return Paths.get(System.getProperty("user.home"), ALLURE_WS); //$NON-NLS-1$
    }

    // ---------------------------------------------------------------------
    // Pure provisioning (paths passed in, no EDT workspace — unit-testable)
    // ---------------------------------------------------------------------

    /**
     * Idempotently creates {@code .vanessa/{features,out}} and writes
     * {@code env.sh} / {@code VAParams.json} from the given templates if they are
     * absent. Never overwrites a file the operator has edited.
     *
     * @param projectRoot the on-disk EDT project root
     * @param project the project key
     * @param envTemplate the env.sh template text
     * @param vaparamsTemplate the VAParams.json template text
     * @return the {@code .vanessa} directory path
     * @throws IOException on a filesystem failure
     */
    public static Path provisionLayout(Path projectRoot, String project,
        String envTemplate, String vaparamsTemplate) throws IOException
    {
        Path vanessa = vanessaDir(projectRoot);
        Files.createDirectories(vanessa.resolve(DIR_FEATURES));
        Files.createDirectories(vanessa.resolve(DIR_OUT));
        Map<String, String> vars = placeholders(projectRoot, project);
        writeIfAbsent(vanessa.resolve(DIR_ENV), envTemplate, vars);
        writeIfAbsent(vanessa.resolve(DIR_VAPARAMS), vaparamsTemplate, vars);
        return vanessa;
    }

    /** {@code <projectRoot>/.vanessa}. */
    static Path vanessaDir(Path projectRoot)
    {
        return projectRoot.resolve(VANESSA_DIR);
    }

    // ---------------------------------------------------------------------
    // Derived-resource marking (the .vanessa tree must not be project-indexed)
    // ---------------------------------------------------------------------

    /**
     * Marks a workspace project's {@code .vanessa} folder as a {@link IResource#DERIVED
     * derived} resource so EDT's project index/search treats it as generated output
     * instead of source. {@link #provisionLayout} creates the tree with {@code java.nio},
     * outside the workspace resource model; left unmarked, EDT sweeps the whole folder —
     * {@code features/}, {@code out/}, the run artifacts — into project indexing. Derived
     * keeps it out of indexing, search, marker computation and version-control indicators.
     *
     * <p>Idempotent: also upgrades a folder a previous plugin run left unmarked.</p>
     *
     * @param project the workspace project whose {@code .vanessa} folder to mark
     */
    private static void markVanessaDerived(IProject project)
    {
        if (project == null)
        {
            return;
        }
        try
        {
            IFolder folder = project.getFolder(VANESSA_DIR);
            if (folder.exists())
            {
                folder.setDerived(true);
            }
            else
            {
                // Folder already exists on disk (provisionLayout) but not yet as a workspace
                // resource: FORCE materialises it from disk, DERIVED flags it in one shot.
                folder.create(IResource.FORCE | IResource.DERIVED, true, null);
            }
        }
        catch (CoreException e)
        {
            Activator.logWarning("Could not mark .vanessa derived for " //$NON-NLS-1$
                + project.getName() + ": " + e.getMessage()); //$NON-NLS-1$
        }
    }

    /**
     * Resolves the project by key and marks its {@code .vanessa} folder derived. Soft:
     * a non-EDT project key (or a pure unit run with no workspace) is a no-op, so this
     * can be called from any provisioning path without disturbing the fallback layout.
     *
     * @param project the project key
     */
    static void markVanessaDerived(String project)
    {
        try
        {
            markVanessaDerived(ProjectContext.of(project).project());
        }
        catch (RuntimeException | LinkageError e)
        {
            // No EDT workspace here (pure unit run): nothing to mark.
        }
    }

    /** Ordered {@code .vanessa} / legacy env.sh candidates, most-preferred first. */
    static List<Path> envCandidates(Path projectRoot, String project)
    {
        return VanessaProjectConfig.resolveEnvCandidates(projectRoot, project,
            System.getProperty("user.home")); //$NON-NLS-1$
    }

    /** First existing candidate, or {@code null}. */
    static Path activeEnv(Path projectRoot, String project)
    {
        for (Path c : envCandidates(projectRoot, project))
        {
            if (c != null && Files.isRegularFile(c))
            {
                return c;
            }
        }
        return null;
    }

    /**
     * Rewrites/inserts the {@code EPF="<path>"} line of a provisioned env.sh so a
     * lazily downloaded runtime is picked up on later runs. Preserves the other
     * lines; idempotent (a later run with the same path changes nothing).
     *
     * @param env the env.sh path to update
     * @param epf the absolute epf path to record
     * @throws IOException if the env.sh cannot be read/written
     */
    static void updateEpfInEnv(Path env, Path epf) throws IOException
    {
        if (env == null || !Files.isRegularFile(env))
        {
            return;
        }
        String needle = "EPF="; //$NON-NLS-1$
        List<String> lines = Files.readAllLines(env, StandardCharsets.UTF_8);
        int replaceAt = -1;
        for (int i = 0; i < lines.size(); i++)
        {
            if (lines.get(i).trim().startsWith(needle))
            {
                replaceAt = i;
                break;
            }
        }
        String line = "EPF=\"" + epf + "\""; //$NON-NLS-1$ //$NON-NLS-2$
        if (replaceAt >= 0)
        {
            lines.set(replaceAt, line);
        }
        else
        {
            lines.add(line);
        }
        Files.write(env, lines, StandardCharsets.UTF_8);
    }


    /**
     * Builds the placeholder map for the templates ({@code @KEY@} tokens).
     *
     * @param projectRoot the on-disk EDT project root (may be null in tests)
     * @param project the project key
     * @return the token map (never null)
     */
    static Map<String, String> placeholders(Path projectRoot, String project)
    {
        Map<String, String> v = new LinkedHashMap<>();
        String home = System.getProperty("user.home"); //$NON-NLS-1$
        v.put("PROJECT", project); //$NON-NLS-1$
        v.put("USER_HOME", home); //$NON-NLS-1$
        v.put("EPF", epfCacheFile().toString()); //$NON-NLS-1$
        if (projectRoot != null)
        {
            Path vanessa = vanessaDir(projectRoot);
            v.put("VAPARAMS", vanessa.resolve(DIR_VAPARAMS).toString()); //$NON-NLS-1$
            v.put("FEATURES_DIR", vanessa.resolve(DIR_FEATURES).toString()); //$NON-NLS-1$
        }
        return v;
    }

    private static void writeIfAbsent(Path file, String template, Map<String, String> vars)
        throws IOException
    {
        if (Files.exists(file))
        {
            return;
        }
        Path parent = file.getParent();
        if (parent != null)
        {
            Files.createDirectories(parent);
        }
        Files.writeString(file, fillTemplate(template == null ? "" : template, vars), //$NON-NLS-1$
            StandardCharsets.UTF_8);
    }

    /** Replaces {@code @KEY@} tokens with their values. Pure. */
    static String fillTemplate(String template, Map<String, String> vars)
    {
        if (template == null)
        {
            return ""; //$NON-NLS-1$
        }
        String out = template;
        if (vars != null)
        {
            for (Map.Entry<String, String> e : vars.entrySet())
            {
                if (e.getKey() == null)
                {
                    continue;
                }
                out = out.replace("@" + e.getKey() + "@", //$NON-NLS-1$ //$NON-NLS-2$
                    e.getValue() == null ? "" : e.getValue()); //$NON-NLS-1$
            }
        }
        return out;
    }

    // ---------------------------------------------------------------------
    // Doctor (read-only readiness report; pure, no download)
    // ---------------------------------------------------------------------

    /**
     * Per-prerequisite readiness booleans. {@link #ready()} is {@code true} when a
     * BDD run can start (layout + env.sh + VAParams + epf present); Allure and the
     * legacy vrunner CLI are optional and never block readiness.
     */
    public static final class Doctor
    {
        public final boolean layout;   // .vanessa/{features,out} + env.sh + VAParams present
        public final boolean envSh;    // active env.sh file exists
        public final boolean vaparams; // VAParams.json exists (provisioned or referenced)
        public final boolean epf;      // epf runtime file present (env EPF or shared cache)
        public final boolean allure;   // Allure CLI resolvable
        public final boolean vrunner;  // informational: legacy vrunner CLI (not required)

        Doctor(boolean layout, boolean envSh, boolean vaparams, boolean epf,
            boolean allure, boolean vrunner)
        {
            this.layout = layout;
            this.envSh = envSh;
            this.vaparams = vaparams;
            this.epf = epf;
            this.allure = allure;
            this.vrunner = vrunner;
        }

        /** Whether a BDD run can start. Allure/vrunner are not required. */
        public boolean ready()
        {
            return layout && envSh && vaparams && epf;
        }
    }

    /**
     * Computes readiness for a project from paths only.
     *
     * @param projectRoot the on-disk EDT project root, or {@code null}
     * @param project the project key
     * @return the doctor report
     */
    public static Doctor doctor(Path projectRoot, String project)
    {
        Path vanessa = projectRoot == null ? null : vanessaDir(projectRoot);
        Path env = activeEnv(projectRoot, project);
        boolean envSh = env != null;
        boolean vaparams = vaparamsPresent(projectRoot, project);
        boolean epf = epfPresent(env);
        return new Doctor(
            vanessa != null && Files.isDirectory(vanessa.resolve(DIR_FEATURES))
                && Files.isDirectory(vanessa.resolve(DIR_OUT)) && envSh && vaparams,
            envSh,
            vaparams,
            epf,
            allurePresent(),
            vrunnerPresent());
    }

    private static boolean vaparamsPresent(Path projectRoot, String project)
    {
        if (projectRoot != null && Files.isRegularFile(vanessaDir(projectRoot).resolve(DIR_VAPARAMS)))
        {
            return true;
        }
        Path env = activeEnv(projectRoot, project);
        if (env == null)
        {
            return false;
        }
        String vaparams = VanessaProjectConfig.parseEnvFile(env).get("VAPARAMS"); //$NON-NLS-1$
        return vaparams != null && !vaparams.trim().isEmpty() && Files.isRegularFile(Paths.get(vaparams));
    }

    private static boolean epfPresent(Path env)
    {
        if (Files.isRegularFile(epfCacheFile()))
        {
            return true;
        }
        if (env == null)
        {
            return false;
        }
        String epf = VanessaProjectConfig.parseEnvFile(env).get("EPF"); //$NON-NLS-1$
        return epf != null && !epf.trim().isEmpty() && Files.isRegularFile(Paths.get(epf));
    }

    private static boolean allurePresent()
    {
        try (DirectoryStream<Path> s = Files.newDirectoryStream(allureCacheDir()))
        {
            for (Path d : s)
            {
                if (Files.isDirectory(d)
                    && Files.isRegularFile(d.resolve("bin").resolve("allure"))) //$NON-NLS-1$ //$NON-NLS-2$
                {
                    return true;
                }
            }
        }
        catch (IOException e)
        {
            // cache dir absent/unreadable -> no Allure
        }
        return false;
    }

    private static boolean vrunnerPresent()
    {
        // Informational only for the legacy CLI path (harness-side, UMFO-13134):
        // a VA run driven through EDT does not need vrunner.
        return false;
    }

    // ---------------------------------------------------------------------
    // Runtime download (lazy; network — runs on a Job, never the UI thread)
    // ---------------------------------------------------------------------

    /**
     * Ensures the VA epf is present in the shared cache, downloading and extracting
     * the pinned release when it is not. Blocks; call from a background job.
     *
     * @return the absolute epf path
     * @throws IOException if the download or extraction fails
     */
    public static Path ensureEpfDownloaded() throws IOException
    {
        Path epf = epfCacheFile();
        if (Files.isRegularFile(epf))
        {
            return epf;
        }
        Path dir = epfCacheDir();
        Files.createDirectories(dir);
        String url = "https://github.com/" + VA_REPO //$NON-NLS-1$
            + "/releases/download/" + VA_TAG + "/" + VA_ZIP; //$NON-NLS-1$ //$NON-NLS-2$
        Path zip = dir.resolve(VA_ZIP);
        download(url, zip);
        extractZip(zip, dir);
        relocateEpfToRoot(dir);
        if (!Files.isRegularFile(epf))
        {
            throw new IOException("Downloaded VA release " + VA_TAG //$NON-NLS-1$
                + " did not contain " + EPF_FILE + " (extracted into " + dir + ")"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
        return epf;
    }

    /**
     * Ensures the Allure CLI is installed in the shared cache (downloads + extracts
     * the pinned distribution via the OS {@code tar}), so
     * {@code vanessa_open_allure_report} works without manual setup.
     *
     * @return the resolved {@code allure} executable, or {@code null} on failure
     */
    public static Path ensureAllureInstalled() throws IOException
    {
        Path existing = highestAllureBin();
        if (existing != null)
        {
            return existing;
        }
        Path dir = allureCacheDir();
        Files.createDirectories(dir);
        String url = "https://github.com/" + ALLURE_REPO //$NON-NLS-1$
            + "/releases/download/" + ALLURE_TAG + "/" + ALLURE_TGZ; //$NON-NLS-1$ //$NON-NLS-2$
        Path tgz = dir.resolve(ALLURE_TGZ);
        download(url, tgz);
        extractTgz(tgz, dir);
        Path bin = highestAllureBin();
        if (bin == null)
        {
            throw new IOException("Allure " + ALLURE_TAG + " extracted but no bin/allure found in " + dir); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return bin;
    }

    /** Highest {@code <allureCache>/allure-&lt;version&gt;/bin/allure}, or {@code null}. */
    private static Path highestAllureBin()
    {
        try (DirectoryStream<Path> s = Files.newDirectoryStream(allureCacheDir()))
        {
            Path best = null;
            for (Path d : s)
            {
                if (!Files.isDirectory(d))
                {
                    continue;
                }
                Path bin = d.resolve("bin").resolve("allure"); //$NON-NLS-1$ //$NON-NLS-2$
                if (Files.isRegularFile(bin)
                    && (best == null
                        || d.getFileName().toString().compareTo(best.getFileName().toString()) > 0))
                {
                    best = bin;
                }
            }
            return best;
        }
        catch (IOException e)
        {
            return null;
        }
    }

    static void download(String url, Path target) throws IOException
    {
        try
        {
            HttpClient client = HttpClient.newBuilder().followRedirects(
                HttpClient.Redirect.NORMAL).build(); //$NON-NLS-1$
            HttpRequest request = HttpRequest.newBuilder(URI.create(url)).GET().build();
            HttpResponse<InputStream> response = client.send(request,
                HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() / 100 != 2)
            {
                throw new IOException("HTTP " + response.statusCode() + " downloading " + url); //$NON-NLS-1$ //$NON-NLS-2$
            }
            try (InputStream in = response.body())
            {
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted downloading " + url, e); //$NON-NLS-1$
        }
    }

    /** Extracts a zip so {@code vanessa-automation.epf} + {@code locales/} land at the root. */
    static void extractZip(Path zip, Path dir) throws IOException
    {
        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(zip),
            StandardCharsets.UTF_8))
        {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null)
            {
                if (entry.isDirectory())
                {
                    continue;
                }
                Path out = sanitized(dir, entry.getName());
                if (out == null)
                {
                    continue;
                }
                if (out.getParent() != null)
                {
                    Files.createDirectories(out.getParent());
                }
                Files.copy(zis, out, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    /**
     * Some VA release zips nest {@link #EPF_FILE} (plus its {@code locales/}) under a
     * top-level folder (e.g. {@code vanessa-automation/}) instead of the archive root.
     * Lifts the {@code .epf} to the cache root — where {@code epfCacheFile()}/
     * {@code provisionVanessaBin} expect it — without disturbing release subfolders.
     * Idempotent: no-op once the root copy already exists.
     */
    static void relocateEpfToRoot(Path dir) throws IOException
    {
        Path root = dir.resolve(EPF_FILE);
        if (Files.isRegularFile(root))
        {
            return;
        }
        try (java.util.stream.Stream<Path> walk = Files.walk(dir))
        {
            Path found = walk.filter(p -> p.getFileName().toString().equals(EPF_FILE)).findFirst().orElse(null);
            if (found != null && !found.equals(root))
            {
                Files.copy(found, root, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    /**
     * Resolves an (archive-relative) entry name under {@code base}, rejecting entries
     * that would escape the extract dir via {@code ..} or an absolute path.
     */
    static Path sanitized(Path base, String name)
    {
        Path target = base.resolve(name.replace('\\', '/')).normalize();
        if (!target.startsWith(base.normalize()))
        {
            return null;
        }
        return target;
    }

    /** Extracts a tar.gz via the OS {@code tar} (zero extra dependency). */
    private static void extractTgz(Path tgz, Path dir) throws IOException
    {
        ProcessBuilder pb = new ProcessBuilder("tar", "-xzf", tgz.toString(), "-C", dir.toString()); //$NON-NLS-1$ //$NON-NLS-2$
        pb.redirectErrorStream(true);
        try
        {
            Process p = pb.start();
            int code = p.waitFor();
            if (code != 0)
            {
                throw new IOException("tar extraction failed (exit " + code + ") for " + tgz); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            throw new IOException("tar extraction interrupted for " + tgz, e); //$NON-NLS-1$
        }
    }

    // ---------------------------------------------------------------------
    // Template resources + Job wrappers
    // ---------------------------------------------------------------------

    /** Reads a bundled {@code templates/<name>} resource, or {@code ""}. */
    public static String template(String name)
    {
        String path = TEMPLATES_DIR + name;
        try
        {
            URL url;
            if (FrameworkUtil.getBundle(VanessaBootstrap.class) != null)
            {
                url = FrameworkUtil.getBundle(VanessaBootstrap.class).getEntry(path);
            }
            else
            {
                url = VanessaBootstrap.class.getClassLoader().getResource(path);
            }
            if (url == null)
            {
                return ""; //$NON-NLS-1$
            }
            try (InputStream in = url.openStream())
            {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
        catch (IOException e)
        {
            return ""; //$NON-NLS-1$
        }
    }

    /**
     * A background job that downloads the VA epf into the shared cache and points a
     * project's {@code .vanessa/env.sh} {@code EPF=} at it.
     *
     * @param projectRoot the project root (may be {@code null})
     * @param project the project key
     * @return the scheduled job
     */
    /** Budget for the lazy epf download ({@link BackgroundJobs}-registered job). */
    private static final long EPF_DOWNLOAD_TIMEOUT_MS = 10L * 60 * 1000;

    /** project -> in-flight download job id (for cross-tool dedup + Pending reporting). */
    private static final java.util.concurrent.ConcurrentMap<String, String> EPF_DOWNLOADS =
        new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Starts (or reports the existence of) the lazy VA-runtime download for a project as a
     * {@link BackgroundJobs} job, so both the run tools' hook and {@code vanessa_setup} share one
     * deduplicated, {@code get_job_status}-pollable download.
     *
     * @param owningTool the tool that triggered the download (job owner)
     * @param projectRoot the project root (may be {@code null})
     * @param project the project key
     * @param installAllure additionally install the Allure CLI
     * @return the started job snapshot, or {@code null} when a download is already in flight
     */
    public static BackgroundJobs.JobSnapshot scheduleEpfDownload(String owningTool,
        Path projectRoot, String project, boolean installAllure)
    {
        // Dedup: several concurrent run-tool calls must not each start a download.
        if (EPF_DOWNLOADS.putIfAbsent(project, "") != null) //$NON-NLS-1$
        {
            return null;
        }
        try
        {
            BackgroundJobs.JobSnapshot job = BackgroundJobs.shared()
                .start(owningTool, EPF_DOWNLOAD_TIMEOUT_MS,
                    "Provisioning VA runtime for " + project, progress -> { //$NON-NLS-1$
                        try
                        {
                            progress.add("Downloading vanessa-automation.epf");
                            Path epf = ensureEpfDownloaded();
                            Path env = projectRoot == null ? null
                                : vanessaDir(projectRoot).resolve(DIR_ENV);
                            if (env != null && Files.isRegularFile(env))
                            {
                                progress.add("Recording EPF in " + env);
                                updateEpfInEnv(env, epf);
                            }
                            if (installAllure)
                            {
                                progress.add("Installing Allure CLI");
                                ensureAllureInstalled();
                            }
                            return "VA runtime " + VA_TAG + " installed at " + epf //$NON-NLS-1$ //$NON-NLS-2$
                                + ". Run vanessa_doctor to confirm readiness.";
                        }
                        catch (IOException e)
                        {
                            Activator.logWarning("VA runtime download failed: " + e); //$NON-NLS-1$
                            throw e;
                        }
                        finally
                        {
                            EPF_DOWNLOADS.remove(project);
                        }
                    });
            EPF_DOWNLOADS.put(project, job.getId());
            return job;
        }
        catch (RuntimeException e)
        {
            EPF_DOWNLOADS.remove(project);
            throw e;
        }
    }

    /** Whether a download for the project is already in flight. */
    static boolean isEpfDownloading(String project)
    {
        return EPF_DOWNLOADS.containsKey(project);
    }

    /**
     * Lazy-run hook for the VA run tools: when this project's epf runtime is not yet
     * installed, starts the download (deduplicated) and returns a human message naming the
     * background job; returns {@code null} when the runtime is ready and the caller may
     * proceed.
     *
     * @param owningTool the tool that triggered the download
     * @param projectRoot the project root (may be null)
     * @param project the project key
     * @return an actionable message naming the background job, or {@code null} when ready
     */
    public static String lazyEpfPending(String owningTool, Path projectRoot, String project)
    {
        if (doctor(projectRoot, project).epf)
        {
            return null;
        }
        String id = epfDownloadJobId(project);
        if (id == null)
        {
            BackgroundJobs.JobSnapshot started =
                scheduleEpfDownload(owningTool, projectRoot, project, false);
            id = started == null ? epfDownloadJobId(project) : started.getId();
            if (id == null)
            {
                return "The Vanessa Automation runtime is not installed for '" + project //$NON-NLS-1$
                    + "' and its download could not start now. Run vanessa_setup, then retry."; //$NON-NLS-1$
            }
        }
        return "The Vanessa Automation runtime is being installed for '" + project //$NON-NLS-1$
            + "' (background job '" + id + "'). Poll it with get_job_status, then re-run " //$NON-NLS-1$ //$NON-NLS-2$
            + "vanessa_doctor / this call once it completes."; //$NON-NLS-1$
    }

    /** Job id of the in-flight download for the project, or {@code null}. */
    static String epfDownloadJobId(String project)
    {
        String id = EPF_DOWNLOADS.get(project);
        return id == null || id.isEmpty() ? null : id;
    }

    // ---------------------------------------------------------------------
    // VA_Runner external-object project provisioning (button-driven)
    // ---------------------------------------------------------------------

    /** Bundle-relative {@code templates/va_runner/} files that form the driver project. */
    static final String[] VA_RUNNER_TEMPLATE_FILES =
    {
        ".project", //$NON-NLS-1$
        ".settings/com._1c.g5.v8.dt.platform.services.core.prefs", //$NON-NLS-1$
        "DT-INF/PROJECT.PMF", //$NON-NLS-1$
        "src/ExternalDataProcessors/VA_Runner/VA_Runner.mdo", //$NON-NLS-1$
        "src/ExternalDataProcessors/VA_Runner/ObjectModule.bsl", //$NON-NLS-1$
        "src/ExternalDataProcessors/VA_Runner/Forms/Форма/Form.form", //$NON-NLS-1$
        "src/ExternalDataProcessors/VA_Runner/Forms/Форма/Module.bsl", //$NON-NLS-1$
    };

    /** URL of the pinned VA release zip asset. */
    static String vaReleaseUrl(String asset)
    {
        return "https://github.com/" + VA_REPO //$NON-NLS-1$
            + "/releases/download/" + VA_TAG + "/" + asset; //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Guarantees the full VA release is downloaded and extracted into the shared cache,
     * including the {@value #RELEASE_SUBDIR} runtime tree the project {@code bin/} is built from.
     * Reuses the lazily-downloaded {@code epf} when present; otherwise (re)downloads the zip.
     *
     * @return the extracted release root ({@code <cache>/<RELEASE_SUBDIR>})
     * @throws IOException on a download/extract failure
     */
    static Path ensureReleaseExtracted() throws IOException
    {
        if (!Files.isRegularFile(epfCacheFile()))
        {
            ensureEpfDownloaded();
        }
        Path relRoot = epfCacheDir().resolve(RELEASE_SUBDIR);
        if (!Files.isDirectory(relRoot))
        {
            Path dir = epfCacheDir();
            Files.createDirectories(dir);
            Path zip = dir.resolve(VA_ZIP);
            download(vaReleaseUrl(VA_ZIP), zip);
            extractZip(zip, dir);
            relocateEpfToRoot(dir);
        }
        return relRoot;
    }

    /**
     * Builds the project's {@code bin/} runtime tree from the extracted VA release:
     * the {@link #BIN_DIRS} folders plus the {@code .epf}, copied under {@code <root>/bin/}.
     * Idempotent — never overwrites files already present in {@code bin/}.
     *
     * @param projectVaRoot the VA_Runner project root
     * @return the created {@code bin/} directory
     * @throws IOException on a download or copy failure
     */
    public static Path provisionVanessaBin(Path projectVaRoot) throws IOException
    {
        Path release = ensureReleaseExtracted();
        Path bin = projectVaRoot.resolve("bin"); //$NON-NLS-1$
        for (String d : BIN_DIRS)
        {
            copyDir(release.resolve(d), bin.resolve(d), false);
        }
        copyFileIfAbsent(release.resolve(EPF_FILE), bin.resolve(EPF_FILE));
        return bin;
    }

    /**
     * Copies the operator's EDT source of the {@code VanessaAutomation} processing from a
     * template directory into the project as {@code src/ExternalDataProcessors/VanessaAutomation}.
     * Idempotent: an already-provisioned non-empty target is left untouched so operator edits survive.
     *
     * @param templateDir where the {@code VanessaAutomation} EDT-source template lives (may be null)
     * @param projectVaRoot the VA_Runner project root
     * @return the destination source directory
     * @throws IOException if the template is missing/unreadable or the copy fails
     */
    public static Path copyVanessaAutomationSource(Path templateDir, Path projectVaRoot)
        throws IOException
    {
        if (templateDir == null || !Files.isDirectory(templateDir))
        {
            throw new IOException("VanessaAutomation EDT-source template not found at " //$NON-NLS-1$
                + (templateDir == null ? "<null>" : templateDir)); //$NON-NLS-1$ //$NON-NLS-2$
        }
        Path dst = projectVaRoot.resolve("src").resolve("ExternalDataProcessors") //$NON-NLS-1$ //$NON-NLS-2$
            .resolve("VanessaAutomation"); //$NON-NLS-1$
        if (!Files.isDirectory(dst) || isEmptyDir(dst))
        {
            copyDir(templateDir, dst, false);
        }
        return dst;
    }

    /**
     * Writes the VA_Runner driver project boilerplate from the bundled {@code templates/va_runner/}
     * tree ({@code .project}, {@code .settings}, {@code DT-INF/PROJECT.PMF} with the given base
     * project, {@code src/ExternalDataProcessors/VA_Runner}) and the {@code .vanessa/} layout.
     * Never overwrites an existing file.
     *
     * @param projectVaRoot the VA_Runner project root
     * @param baseProject the base configuration project name ({@code Base-Project} in {@code PROJECT.PMF})
     * @return the project root
     * @throws IOException on a write failure
     */
    public static Path writeVaRunnerBoilerplate(Path projectVaRoot, String baseProject)
        throws IOException
    {
        Files.createDirectories(projectVaRoot);
        Map<String, String> vars = new LinkedHashMap<>();
        vars.put("BASE_PROJECT", baseProject == null ? "" : baseProject); //$NON-NLS-1$ //$NON-NLS-2$
        for (String rel : VA_RUNNER_TEMPLATE_FILES)
        {
            String t = template("va_runner/" + rel); //$NON-NLS-1$
            if (!t.isEmpty())
            {
                writeIfAbsent(projectVaRoot.resolve(rel), t, vars);
            }
        }
        provisionLayout(projectVaRoot, "VA_Runner", //$NON-NLS-1$
            template("env.sh.template"), template("VAParams.json.template")); //$NON-NLS-1$ //$NON-NLS-2$
        return projectVaRoot;
    }

    /** Whether {@code dir} exists and holds no files. */
    private static boolean isEmptyDir(Path dir)
    {
        if (!Files.isDirectory(dir))
        {
            return true;
        }
        try (var s = Files.list(dir))
        {
            return s.findFirst().isEmpty();
        }
        catch (IOException e)
        {
            return true;
        }
    }

    /** Recursively copies a directory tree; existing target files are kept when {@code overwrite} is false. */
    static void copyDir(Path src, Path dst, boolean overwrite) throws IOException
    {
        if (!Files.isDirectory(src))
        {
            return;
        }
        try (var stream = Files.walk(src))
        {
            for (Path p : (Iterable<Path>) stream::iterator)
            {
                Path target = dst.resolve(src.relativize(p));
                if (Files.isDirectory(p))
                {
                    Files.createDirectories(target);
                }
                else
                {
                    if (!overwrite && Files.exists(target))
                    {
                        continue;
                    }
                    if (target.getParent() != null)
                    {
                        Files.createDirectories(target.getParent());
                    }
                    Files.copy(p, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    /** Copies a file only when the target does not exist yet. */
    static void copyFileIfAbsent(Path src, Path dst) throws IOException
    {
        if (!Files.isRegularFile(src) || Files.exists(dst))
        {
            return;
        }
        if (dst.getParent() != null)
        {
            Files.createDirectories(dst.getParent());
        }
        Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
    }
}
