/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils.snapshot;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Element;

import com.ditrix.edt.mcp.server.utils.BackgroundJobs;
import com.ditrix.edt.mcp.server.utils.BackgroundJobs.JobSnapshot;
import com.ditrix.edt.mcp.server.utils.BackgroundJobs.ProgressReporter;
import com.ditrix.edt.mcp.server.protocol.GsonProvider;
import com.ditrix.edt.mcp.server.utils.JUnitMarkdownFormatter;
import com.ditrix.edt.mcp.server.utils.JUnitTestResults;
import com.ditrix.edt.mcp.server.utils.JUnitXmlParser;
import com.ditrix.edt.mcp.server.utils.snapshot.ProjectSnapshotService.BuildAdmission;
import com.ditrix.edt.mcp.server.utils.snapshot.ProjectSnapshotService.BuildAuthorization;

/** Builds an authenticated source capture into a disposable Windows file infobase. */
public final class SnapshotYaxunitRunner
{
    private static final String SANDBOX_PROPERTY = "edt.mcp.yaxunit.sandbox";
    private static final String JOB_PREFIX = "snapshot-yaxunit-";
    private static final long TIME_LIMIT_MS = TimeUnit.MINUTES.toMillis(5);
    private static final int MAX_SEED_BYTES = 64 * 1024 * 1024;
    private static final long MAX_REPORT_BYTES = 4 * 1024 * 1024L;
    private static final long MAX_JOB_BYTES = 1024 * 1024 * 1024L;
    private static final int MAX_JOB_ENTRIES = 60_000;
    private static final Pattern EXTENSION = Pattern.compile("[\\p{L}_][\\p{L}\\p{N}_]{0,79}");
    private static final String MD_NAMESPACE = "http://v8.1c.ru/8.3/MDClasses";

    private SnapshotYaxunitRunner()
    {
    }

    /** Admits one new job using only the retained payload and the operator's toolchain. */
    public static JobSnapshot start(BackgroundJobs jobs, String tool, UUID snapshotId,
        Map<String, List<String>> selectors) throws IOException
    {
        BuildAdmission admission = ProjectSnapshotService.authorizeBuild(snapshotId);
        JobSnapshot job = null;
        try
        {
            BuildAuthorization authorization = admission.authorization();
            if (selectors.values().stream().flatMap(List::stream).mapToLong(String::length).sum() > 16_384)
            {
                throw new IOException("Snapshot YAxUnit selectors are too large.");
            }
            Toolchain configuration = Toolchain.load();
            if (authorization.scope().stream().map(grant -> Path.of(grant.location()))
                .anyMatch(project -> configuration.root().startsWith(project) || project.startsWith(configuration.root())))
            {
                throw new IOException("The snapshot YAxUnit job root must be separate from the captured projects.");
            }
            job = jobs.startProtected(tool, TIME_LIMIT_MS, 1, "Accepted the snapshot YAxUnit request.",
                authorization::permitsCaller, admission::close,
                progress -> execute(admission, configuration, selectors, progress));
            return job;
        }
        finally
        {
            if (job == null)
            {
                admission.close();
            }
        }
    }

    private static String execute(BuildAdmission admission, Toolchain configuration,
        Map<String, List<String>> selectors, ProgressReporter progress) throws IOException, InterruptedException
    {
        BuildAuthorization authorization = admission.authorization();
        String phase = "sandbox preparation";
        try (FileChannel channel = FileChannel.open(configuration.root().resolve(".snapshot-yaxunit.lock"),
            LinkOption.NOFOLLOW_LINKS, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            FileLock lock = channel.tryLock())
        {
            if (lock == null)
            {
                throw new IOException("The configured run root is in use.");
            }
            removeAbandonedJobs(configuration.root());
            try (JobDirectory directory = new JobDirectory(Files.createTempDirectory(configuration.root(), JOB_PREFIX)))
            {
                Path job = directory.path();
                phase = "source materialization";
                progress.add("Materializing the retained snapshot.");
                var source = admission.materialize(job);
                Map<String, String> environment = configuration.environment(job);
                Path workspace = Files.createDirectory(job.resolve("workspace"));
                List<Path> exports = new ArrayList<>();
                List<String> extensionNames = new ArrayList<>();
                Set<String> uniqueNames = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
                uniqueNames.add("YAXUNIT");
                for (Path project : source.projects())
                {
                    phase = exports.isEmpty() ? "base configuration export" : "extension export";
                    progress.add("Running " + phase + " in a private EDT workspace.");
                    Path output = Files.createDirectory(job.resolve("output-" + exports.size()));
                    Path script = job.resolve("export-" + exports.size() + ".txt");
                    Files.writeString(script, "export --project \"" + project + "\" --configuration-files \""
                        + output + "\"\n", StandardOpenOption.CREATE_NEW);
                    launch(configuration.cli(), List.of("-data", workspace.toString(), "-file", script.toString()),
                        job, environment, authorization, progress);
                    String name = configurationName(output.resolve("Configuration.xml"));
                    if (!exports.isEmpty())
                    {
                        if (!uniqueNames.add(name))
                        {
                            throw new IOException("Duplicate or reserved extension name.");
                        }
                        extensionNames.add(name);
                    }
                    exports.add(output);
                }

                phase = "engine template validation";
                progress.add("Creating the job's file infobase from the pinned engine template.");
                Path database = Files.createDirectory(job.resolve("infobase"));
                configuration.seed(database);
                for (int index = 0; index < exports.size(); index++)
                {
                    phase = index == 0 ? "base configuration load" : "extension load";
                    progress.add("Running " + phase + " in the job's file infobase.");
                    String extension = index == 0 ? null : extensionNames.get(index - 1);
                    List<String> load = platformArguments("DESIGNER", database, job.resolve("designer-load.log"));
                    load.addAll(List.of("/LoadConfigFromFiles", exports.get(index).toString()));
                    appendExtension(load, extension);
                    launch(configuration.designer(), load, job, environment, authorization, progress);
                    phase = index == 0 ? "base configuration update" : "extension update";
                    List<String> update = platformArguments("DESIGNER", database, job.resolve("designer-update.log"));
                    update.addAll(List.of("/UpdateDBCfg", "-Dynamic-"));
                    appendExtension(update, extension);
                    launch(configuration.designer(), update, job, environment, authorization, progress);
                }

                phase = "thin-client execution";
                progress.add("Running YAxUnit in the job's file infobase.");
                Path report = job.resolve("junit.xml");
                Path parameters = job.resolve("parameters.json");
                Files.writeString(parameters, GsonProvider.toJson(Map.of("reportPath", report.toString(),
                    "reportFormat", "jUnit", "closeAfterTests", true, "filter", selectors)),
                    StandardOpenOption.CREATE_NEW);
                List<String> run = platformArguments("ENTERPRISE", database, job.resolve("thin-client.log"));
                run.addAll(List.of("/C", "RunUnitTests=" + parameters));
                launch(configuration.thinClient(), run, job, environment, authorization, progress);

                phase = "JUnit verification";
                JUnitTestResults results = readReport(report);
                phase = "sandbox cleanup";
                return evidence(authorization.id(), progress.jobId(), source.fingerprints(), selectors, results);
            }
        }
        catch (InterruptedException e)
        {
            throw e;
        }
        catch (ProcessFailure e)
        {
            throw new IOException("Snapshot YAxUnit failed during " + phase + " (exit code " + e.exitCode + ").");
        }
        catch (Exception | LinkageError e)
        {
            throw new IOException("Snapshot YAxUnit failed during " + phase + ".");
        }
        finally
        {
            authorization.verify();
        }
    }

    private static String evidence(UUID snapshotId, String jobId, Map<String, String> fingerprints,
        Map<String, List<String>> selectors, JUnitTestResults results)
    {
        String provenance = GsonProvider.toJson(Map.of("snapshotId", snapshotId.toString(), "jobId", jobId,
            "sourceFingerprints", fingerprints, "selectors", selectors,
            "executedTests", results.getTotal() - results.getSkipped()));
        // Preserve the selectors as JSON without allowing a caller-provided Markdown delimiter.
        return "## Snapshot run provenance\n\n```json\n" + provenance.replace("`", "\\u0060") + "\n```\n\n"
            + JUnitMarkdownFormatter.format(results);
    }

    private static List<String> platformArguments(String mode, Path database, Path log) throws IOException
    {
        Files.deleteIfExists(log);
        return new ArrayList<>(List.of(mode, "/F", database.toString(), "/DisableStartupDialogs",
            "/DisableStartupMessages", "/L", "en", "/Out", log.toString()));
    }

    private static void appendExtension(List<String> arguments, String extension)
    {
        if (extension != null)
        {
            arguments.addAll(List.of("-Extension", extension));
        }
    }

    private static void launch(Path executable, List<String> arguments, Path job, Map<String, String> environment,
        BuildAuthorization authorization, ProgressReporter progress) throws IOException, InterruptedException
    {
        checkBudget(progress);
        authorization.verify();
        try (WindowsJobProcess process = WindowsJobProcess.start(executable, arguments, job, environment))
        {
            long measuredAt = System.nanoTime();
            while (!process.finished())
            {
                checkBudget(progress);
                if (System.nanoTime() - measuredAt >= TimeUnit.SECONDS.toNanos(1))
                {
                    checkDirectoryBudget(job);
                    authorization.verify();
                    measuredAt = System.nanoTime();
                }
            }
            int exitCode = process.exitCode();
            if (exitCode != 0)
            {
                throw new ProcessFailure(exitCode);
            }
        }
        checkBudget(progress);
        checkDirectoryBudget(job);
    }

    private static void checkBudget(ProgressReporter progress) throws IOException, InterruptedException
    {
        if (Thread.currentThread().isInterrupted())
        {
            throw new InterruptedException();
        }
        if (progress.remainingMillis() == 0)
        {
            throw new IOException("The snapshot execution deadline elapsed.");
        }
    }

    private static void checkDirectoryBudget(Path job) throws IOException
    {
        Files.walkFileTree(job, new SimpleFileVisitor<Path>()
        {
            private long total;
            private int count;

            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) throws IOException
            {
                countEntry();
                try
                {
                    if (!directory.toRealPath().equals(directory))
                    {
                        throw new IOException("The isolated job created a linked directory.");
                    }
                }
                catch (NoSuchFileException disappeared)
                {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException
            {
                countEntry();
                total += attributes.size();
                if (total > MAX_JOB_BYTES)
                {
                    throw new IOException("The job exceeded its file size limit.");
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException failure) throws IOException
            {
                if (failure instanceof NoSuchFileException)
                {
                    return FileVisitResult.CONTINUE;
                }
                throw failure;
            }

            private void countEntry() throws IOException
            {
                if (++count > MAX_JOB_ENTRIES || Thread.currentThread().isInterrupted())
                {
                    throw new IOException("The job exceeded its file count limit or was interrupted.");
                }
            }
        });
    }

    private static String configurationName(Path artifact) throws Exception
    {
        requireArtifact(artifact);
        DocumentBuilderFactory xml = DocumentBuilderFactory.newInstance();
        xml.setNamespaceAware(true);
        xml.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        xml.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        xml.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        Element root = xml.newDocumentBuilder().parse(artifact.toFile()).getDocumentElement();
        Element configuration = child(root, "Configuration");
        String name = child(child(configuration, "Properties"), "Name").getTextContent();
        if (!EXTENSION.matcher(name).matches())
        {
            throw new IOException("The exported configuration name is invalid.");
        }
        return name;
    }

    private static Element child(Element parent, String localName) throws IOException
    {
        for (var node = parent.getFirstChild(); node != null; node = node.getNextSibling())
        {
            if (node instanceof Element element && localName.equals(element.getLocalName())
                && MD_NAMESPACE.equals(element.getNamespaceURI()))
            {
                return element;
            }
        }
        throw new IOException("The exported configuration has an incomplete descriptor.");
    }

    static JUnitTestResults readReport(Path report) throws Exception
    {
        requireArtifact(report);
        return JUnitXmlParser.parseYaxunitReport(report.toFile());
    }

    private static void requireArtifact(Path artifact) throws IOException
    {
        if (!Files.isRegularFile(artifact, LinkOption.NOFOLLOW_LINKS)
            || !artifact.toRealPath().equals(artifact) || Files.size(artifact) > MAX_REPORT_BYTES)
        {
            throw new IOException("The job did not produce a usable artifact.");
        }
    }

    private static void removeAbandonedJobs(Path root) throws IOException
    {
        try (var children = Files.newDirectoryStream(root, JOB_PREFIX + "*"))
        {
            for (Path child : children)
            {
                if (child.getFileName().toString().matches(JOB_PREFIX + "[0-9]{1,20}")
                    && Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS) && child.toRealPath().equals(child))
                {
                    deleteTree(child);
                }
            }
        }
    }

    private static void deleteTree(Path root) throws IOException
    {
        Files.walkFileTree(root, new SimpleFileVisitor<Path>()
        {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) throws IOException
            {
                if (!directory.toRealPath().equals(directory))
                {
                    Files.delete(directory);
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException
            {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path directory, IOException failure) throws IOException
            {
                if (failure != null)
                {
                    throw failure;
                }
                Files.delete(directory);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private record JobDirectory(Path path) implements AutoCloseable
    {
        @Override
        public void close() throws IOException
        {
            deleteTree(path);
        }
    }

    private static final class ProcessFailure extends IOException
    {
        private static final long serialVersionUID = 1L;
        private final int exitCode;

        ProcessFailure(int exitCode)
        {
            this.exitCode = exitCode;
        }
    }

    private record Settings(String root, String edtCli, String javaHome, String platformDir, String seed,
        String seedSha256) { }

    private record Toolchain(Path root, Path cli, Path javaHome, Path designer, Path thinClient, Path seedFile,
        byte[] digest)
    {
        static Toolchain load() throws IOException
        {
            try
            {
                Settings settings = GsonProvider.fromJson(System.getProperty(SANDBOX_PROPERTY), Settings.class);
                WindowsSourceTree windows = WindowsSourceTree.load();
                Path root = directory(windows, settings.root());
                Path cli = executable(windows, settings.edtCli(), "1cedtcli.exe");
                Path java = directory(windows, settings.javaHome());
                executable(windows, java.resolve("bin/java.exe").toString(), "java.exe");
                Path platform = directory(windows, settings.platformDir());
                Path designer = executable(windows, platform.resolve("1cv8.exe").toString(), "1cv8.exe");
                Path thin = executable(windows, platform.resolve("1cv8c.exe").toString(), "1cv8c.exe");
                Path seedFile = localPath(settings.seed());
                directory(windows, seedFile.getParent().toString());
                if (!"1Cv8.1CD".equalsIgnoreCase(seedFile.getFileName().toString()) || seedFile.startsWith(root)
                    || !settings.seedSha256().matches("[a-fA-F0-9]{64}"))
                {
                    throw new IOException();
                }
                return new Toolchain(root, cli, java, designer, thin, seedFile,
                    HexFormat.of().parseHex(settings.seedSha256()));
            }
            catch (IOException | RuntimeException | LinkageError e)
            {
                throw new IOException("Snapshot YAxUnit requires a valid local Windows toolchain and pinned engine template.");
            }
        }

        private static Path localPath(String value) throws IOException
        {
            Path path = Path.of(value);
            if (!path.equals(path.normalize()) || !WindowsSourceTree.isDrivePath(path.toString()))
            {
                throw new IOException();
            }
            return path;
        }

        private static Path directory(WindowsSourceTree windows, String value) throws IOException
        {
            Path path = localPath(value);
            try (var handle = windows.openProject(path.toString()))
            {
                return path.toRealPath();
            }
        }

        private static Path executable(WindowsSourceTree windows, String value, String name) throws IOException
        {
            Path path = localPath(value);
            Path actual = path.toRealPath();
            if (!name.equalsIgnoreCase(path.getFileName().toString()) || !Files.isRegularFile(path,
                LinkOption.NOFOLLOW_LINKS) || !directory(windows, path.getParent().toString()).equals(actual.getParent()))
            {
                throw new IOException();
            }
            return actual;
        }

        void seed(Path database) throws IOException, NoSuchAlgorithmException
        {
            WindowsSourceTree windows = WindowsSourceTree.load();
            try (var dir = windows.openProject(seedFile.getParent().toString()))
            {
                byte[] image = dir.read(seedFile.getFileName().toString(), MAX_SEED_BYTES);
                if (!MessageDigest.isEqual(MessageDigest.getInstance("SHA-256").digest(image), digest))
                {
                    throw new IOException("The engine template digest does not match.");
                }
                Files.write(database.resolve("1Cv8.1CD"), image, StandardOpenOption.CREATE_NEW);
            }
        }

        Map<String, String> environment(Path job) throws IOException
        {
            Map<String, String> environment = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            for (String name : List.of("SystemRoot", "SystemDrive", "ProgramData", "ProgramFiles",
                "ProgramFiles(x86)", "CommonProgramFiles", "CommonProgramFiles(x86)", "ComputerName",
                "OS", "ComSpec", "NUMBER_OF_PROCESSORS", "PROCESSOR_ARCHITECTURE"))
            {
                String value = System.getenv(name);
                if (value != null)
                {
                    environment.put(name, value);
                }
            }
            Path home = Files.createDirectory(job.resolve("home"));
            Path local = Files.createDirectory(home.resolve("Local"));
            Path roaming = Files.createDirectory(home.resolve("Roaming"));
            Path temp = Files.createDirectory(job.resolve("tmp"));
            environment.put("USERPROFILE", home.toString());
            environment.put("APPDATA", roaming.toString());
            environment.put("LOCALAPPDATA", local.toString());
            environment.put("HOME", home.toString());
            environment.put("TEMP", temp.toString());
            environment.put("TMP", temp.toString());
            environment.put("JAVA_HOME", javaHome.toString());
            environment.put("Path", javaHome.resolve("bin") + ";" + designer.getParent() + ";"
                + Path.of(environment.get("SystemRoot"), "System32"));
            return environment;
        }
    }
}
