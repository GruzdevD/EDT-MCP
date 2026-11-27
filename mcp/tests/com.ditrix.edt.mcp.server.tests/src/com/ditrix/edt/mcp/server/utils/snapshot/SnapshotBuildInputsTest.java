/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.utils.snapshot;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import com.ditrix.edt.mcp.server.utils.snapshot.ProjectSourceManifest.Entry;
import com.ditrix.edt.mcp.server.utils.snapshot.ProjectSourceManifest.Project;
import com.ditrix.edt.mcp.server.utils.snapshot.ProjectSourceManifest.Version;

public class SnapshotBuildInputsTest
{
    private static final String CONFIG_PATH = "src/Configuration/Configuration.mdo";
    private static final String CALC_PATH = "src/CommonModules/Вычисление/Вычисление.mdo";
    private static final String ROLE_PATH = "src/Roles/Reader/Reader.mdo";

    @Test
    public void descriptorAcceptsAnOrdinaryEdtProjectAndRejectsLinksOrForeignBuilders() throws IOException
    {
        assertEquals("8.5.1", SnapshotBuildInputs.validate("Base", descriptors("", "xtextBuilder")).runtime());
        assertThrows(IOException.class,
            () -> SnapshotBuildInputs.validate("Base", descriptors("<linkedResources/>", "xtextBuilder")));
        assertThrows(IOException.class,
            () -> SnapshotBuildInputs.validate("Base", descriptors("", "otherBuilder")));
        assertThrows(IOException.class,
            () -> SnapshotBuildInputs.validate("Another", descriptors("", "xtextBuilder")));
        assertFalse(ProjectSourceManifest.safeBuildComponent("AUX.bsl"));
        assertFalse(ProjectSourceManifest.safeBuildComponent("CONOUT$.mdo"));
        assertTrue(ProjectSourceManifest.safeBuildComponent("Module.bsl"));
    }

    @Test
    public void retainedDescriptorBytesAffectReadinessAndFingerprint() throws IOException
    {
        var entries = List.of(entry(CONFIG_PATH, metadata("Configuration", "Base", "")));
        var a = new Project("Base", "", "", entries, false,
            SnapshotBuildInputs.validate("Base", descriptors("", "xtextBuilder")));
        var b = new Project("Base", "", "", entries, false,
            SnapshotBuildInputs.validate("Base", descriptors(" ", "xtextBuilder")));
        SnapshotBuildInputs.validateScope(List.of(a));
        assertThrows(IOException.class, () -> SnapshotBuildInputs.validateScope(List.of(
            new Project("Base", "", "", List.of(), false, a.buildInputs()))));
        var observation = new ProjectSnapshotCapture.ModelObservation(false, Map.of());
        assertTrue(ProjectSnapshotCapture.readStable(() -> List.of(a), () -> observation, () -> false).yaxunitReady());
        assertNotEquals(ProjectSnapshotCapture.fingerprints(List.of(a)), ProjectSnapshotCapture.fingerprints(List.of(b)));
    }

    @Test
    public void rejectsMissingMalformedAndMismatchedDeclaredMetadata() throws IOException
    {
        for (String invalid : List.of("", "configuration", "<Configuration/>", metadata("Configuration", "", "")))
        {
            assertThrows(IOException.class, () -> SnapshotBuildInputs.validateScope(
                List.of(project(entry(CONFIG_PATH, invalid)))));
        }
        String members = """
            <languages uuid="38ecdf43-3c5f-49b7-b0aa-26d365a57ee6">
              <name>English</name><languageCode>en</languageCode>
            </languages>
            <commonModules>CommonModule.Вычисление</commonModules>
            <roles>Role.Reader</roles>
            """;
        Entry root = entry(CONFIG_PATH, metadata("Configuration", "Base", members));
        Entry role = entry(ROLE_PATH, metadata("Role", "Reader", ""));
        Entry calc = entry(CALC_PATH, metadata("CommonModule", "Вычисление", ""));
        assertThrows(IOException.class, () -> SnapshotBuildInputs.validateScope(List.of(project(root, role))));
        assertThrows(IOException.class, () -> SnapshotBuildInputs.validateScope(List.of(project(root, role,
            new Entry(CALC_PATH, calc.disk(), calc.disk(), null)))));
        assertThrows(IOException.class, () -> SnapshotBuildInputs.validateScope(List.of(project(root, role,
            entry(CALC_PATH, metadata("CommonModule", "Different", ""))))));
        assertThrows(IOException.class, () -> SnapshotBuildInputs.validateScope(List.of(project(root, role,
            entry(CALC_PATH, metadata("CommonModule", "Вычисление", "").replace("CommonModule", "Catalog"))))));
        SnapshotBuildInputs.validateScope(List.of(project(root, role, calc)));
    }

    @Test
    public void requiresDeclaredNestedSubsystemFilesAtEveryDepth() throws IOException
    {
        Entry root = entry(CONFIG_PATH, metadata("Configuration", "Base",
            "<subsystems>Subsystem.Parent</subsystems>"));
        Entry parent = entry("src/Subsystems/Parent/Parent.mdo",
            metadata("Subsystem", "Parent", "<subsystems>Child</subsystems>"));
        String children = "src/Subsystems/Parent/Subsystems/Child/";
        Entry child = entry(children + "Child.mdo",
            metadata("Subsystem", "Child", "<subsystems>Grandchild</subsystems>"));
        Entry grandchild = entry(children + "Subsystems/Grandchild/Grandchild.mdo",
            metadata("Subsystem", "Grandchild", ""));
        assertThrows(IOException.class, () -> SnapshotBuildInputs.validateScope(List.of(project(root, parent))));
        assertThrows(IOException.class, () -> SnapshotBuildInputs.validateScope(List.of(project(root, parent, child,
            new Entry(grandchild.path(), grandchild.disk(), grandchild.disk(), null)))));
        SnapshotBuildInputs.validateScope(List.of(project(root, parent, child, grandchild)));
    }

    private static Project project(Entry... entries) throws IOException
    {
        return new Project("Base", "", "", List.of(entries), false,
            SnapshotBuildInputs.validate("Base", descriptors("", "xtextBuilder")));
    }

    private static Entry entry(String path, String contents)
    {
        Version data = version(contents);
        return new Entry(path, data, data, data);
    }

    private static String metadata(String type, String name, String contents)
    {
        return """
            <m:%s xmlns:m="http://g5.1c.ru/v8/dt/metadata/mdclass" uuid="38ecdf43-3c5f-49b7-b0aa-26d365a57ee6">
              <name>%s</name>%s
            </m:%s>
            """.formatted(type, name, contents, type);
    }

    static Map<String, Version> descriptors(String extra, String builder)
    {
        String project = """
            <projectDescription>
              <name>Base</name><projects/>
              <buildSpec><buildCommand><name>org.eclipse.xtext.ui.shared.%s</name><arguments/></buildCommand></buildSpec>
              <natures><nature>org.eclipse.xtext.ui.shared.xtextNature</nature>
              <nature>com._1c.g5.v8.dt.core.V8ConfigurationNature</nature></natures>%s
            </projectDescription>
            """.formatted(builder, extra);
        return Map.of(".project", version(project), "DT-INF/PROJECT.PMF",
            version("Manifest-Version: 1.0\r\nRuntime-Version: 8.5.1\r\n"));
    }

    private static Version version(String contents)
    {
        return new Version(contents.getBytes(StandardCharsets.UTF_8));
    }
}
