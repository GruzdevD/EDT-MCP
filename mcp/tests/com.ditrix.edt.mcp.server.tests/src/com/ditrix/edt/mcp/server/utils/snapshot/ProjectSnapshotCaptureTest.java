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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorPart;
import org.junit.Test;

import com.ditrix.edt.mcp.server.utils.snapshot.ProjectSnapshotCapture.ModelObservation;
import com.ditrix.edt.mcp.server.utils.snapshot.ProjectSnapshotCapture.ModelStatus;
import com.ditrix.edt.mcp.server.utils.snapshot.ProjectSourceManifest.Entry;
import com.ditrix.edt.mcp.server.utils.snapshot.ProjectSourceManifest.Project;
import com.ditrix.edt.mcp.server.utils.snapshot.ProjectSourceManifest.Version;

public class ProjectSnapshotCaptureTest
{
    @Test
    public void fingerprintsUnchangedInputsOfTheConfigurationAndTheIncludedExtension() throws IOException
    {
        Project main = project("Configuration", "catalog");
        Project extension = project("Extension", "extension");
        List<Project> scope = List.of(main, extension);
        Map<String, String> fingerprints = ProjectSnapshotCapture.fingerprints(scope);
        assertEquals(Set.of("Configuration", "Extension"), fingerprints.keySet());
        assertNotEquals(fingerprints.get("Extension"),
            ProjectSnapshotCapture.fingerprints(List.of(main, project("Extension", "updated"))).get("Extension"));
        assertEquals(ModelStatus.UNKNOWN, ProjectSnapshotCapture.readStable(() -> scope,
            () -> new ModelObservation(false, Map.of()), () -> false).modelStatus());
        assertTrue(ProjectSnapshotCapture.readStable(() -> scope,
            () -> new ModelObservation(false, fingerprints), () -> false).verified());
        assertThrows(IOException.class, () -> ProjectSnapshotCapture.readStable(() -> scope,
            () -> new ModelObservation(false, fingerprints), () -> true));
    }

    @Test
    public void dirtyEditorWithholdsTheVerificationWithoutSaving()
        throws IOException
    {
        IProject project = mock(IProject.class);
        IEditorInput input = mock(IEditorInput.class);
        IEditorPart dirty = mock(IEditorPart.class);
        IResource resource = mock(IResource.class);
        when(dirty.isDirty()).thenReturn(true);
        when(dirty.getEditorInput()).thenReturn(input);
        when(input.getAdapter(IResource.class)).thenReturn(resource);
        when(resource.getProject()).thenReturn(project);
        boolean hasDirtyEditor = ProjectSnapshotCapture.hasDirtyEditor(Set.of(project), new IEditorPart[] {dirty});
        assertTrue(hasDirtyEditor);

        List<Project> scope = List.of(project("Configuration", "saved source"));
        var snapshot = ProjectSnapshotCapture.readStable(() -> scope,
            () -> new ModelObservation(hasDirtyEditor, ProjectSnapshotCapture.fingerprints(scope)), () -> false);
        assertEquals(ModelStatus.DIVERGED, snapshot.modelStatus());
        assertFalse(snapshot.verified());
        verify(dirty).isDirty();
        verify(dirty).getEditorInput();
        verifyNoMoreInteractions(dirty, project);
    }

    private static Project project(String name, String contents)
    {
        Version file = new Version(contents.getBytes(StandardCharsets.UTF_8));
        return new Project(name, "refs/heads/main", "head", List.of(new Entry("src/Module.bsl", file, file, file)), false);
    }
}
