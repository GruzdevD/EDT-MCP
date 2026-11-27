/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils.snapshot;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IResourceChangeEvent;
import org.eclipse.core.resources.IResourceChangeListener;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;

import com.ditrix.edt.mcp.server.utils.LaunchLifecycleUtils;
import com.ditrix.edt.mcp.server.utils.snapshot.ProjectSourceManifest.Entry;
import com.ditrix.edt.mcp.server.utils.snapshot.ProjectSourceManifest.Project;
import com.ditrix.edt.mcp.server.utils.snapshot.ProjectSourceManifest.Version;
import com.ditrix.edt.mcp.server.utils.snapshot.SnapshotSourcePolicy.Access;
import com.ditrix.edt.mcp.server.utils.snapshot.SnapshotSourcePolicy.ScopedProject;

/** Captures stable inputs and evaluates the evidence available from the running model. */
final class ProjectSnapshotCapture
{
    enum ModelStatus { VERIFIED, DIVERGED, UNKNOWN }

    record Snapshot(List<Project> projects, Map<String, String> sourceFingerprints, ModelStatus modelStatus)
    {
        Snapshot
        {
            projects = List.copyOf(projects);
            sourceFingerprints = Map.copyOf(sourceFingerprints);
        }

        boolean verified()
        {
            return modelStatus == ModelStatus.VERIFIED;
        }

        boolean yaxunitReady()
        {
            return !projects.isEmpty() && projects.get(0).buildInputs() != null;
        }
    }

    record ModelObservation(boolean dirty, Map<String, String> confirmedInputs)
    {
        private static final ModelObservation UNKNOWN = new ModelObservation(false, Map.of());

        ModelObservation
        {
            confirmedInputs = Map.copyOf(confirmedInputs);
        }
    }

    @FunctionalInterface
    interface ReadManifest
    {
        List<Project> read() throws IOException;
    }

    private ProjectSnapshotCapture()
    {
    }

    static Snapshot capture(String project, List<String> extensions, Access access, boolean forYaxunit) throws IOException
    {
        Set<IProject> projects = Set.copyOf(access.projects().stream().map(ScopedProject::project).toList());
        IWorkspace workspace = ResourcesPlugin.getWorkspace();
        AtomicBoolean changed = new AtomicBoolean();
        IResourceChangeListener listener = event -> {
            for (IProject candidate : projects)
            {
                if (candidate.equals(event.getResource()) || event.getDelta() != null
                    && event.getDelta().findMember(candidate.getFullPath()) != null)
                {
                    changed.set(true);
                    break;
                }
            }
        };
        workspace.addResourceChangeListener(listener, IResourceChangeEvent.POST_CHANGE | IResourceChangeEvent.PRE_CLOSE
            | IResourceChangeEvent.PRE_DELETE);
        try
        {
            Snapshot snapshot = readStable(() -> ProjectSourceManifest.capture(access, forYaxunit),
                () -> model(projects), changed::get);
            Access current = SnapshotSourcePolicy.require(project, extensions);
            if (changed.get() || !access.client().equals(current.client())
                || !projects.equals(Set.copyOf(current.projects().stream().map(ScopedProject::project).toList())))
            {
                throw changed();
            }
            SnapshotSourcePolicy.requireGrantScope(access.projects().stream().map(ScopedProject::grant).toList(),
                current.projects().stream().map(ScopedProject::grant).toList(), forYaxunit);
            return snapshot;
        }
        finally
        {
            workspace.removeResourceChangeListener(listener);
        }
    }

    static Snapshot readStable(ReadManifest source, Supplier<ModelObservation> model, BooleanSupplier changed)
        throws IOException
    {
        ModelObservation before = model.get();
        List<Project> captured = source.read();
        Map<String, String> fingerprints = fingerprints(captured);
        List<Project> confirmation = source.read();
        ModelObservation after = model.get();
        if (Thread.currentThread().isInterrupted() || changed.getAsBoolean()
            || !fingerprints.equals(fingerprints(confirmation)) || !sameManifest(captured, confirmation)
            || !before.confirmedInputs().equals(after.confirmedInputs()))
        {
            throw changed();
        }
        ModelStatus status = ModelStatus.UNKNOWN;
        if (before.dirty() || after.dirty())
        {
            status = ModelStatus.DIVERGED;
        }
        else if (!after.confirmedInputs().isEmpty() && after.confirmedInputs().keySet().equals(fingerprints.keySet()))
        {
            status = after.confirmedInputs().equals(fingerprints) ? ModelStatus.VERIFIED : ModelStatus.DIVERGED;
        }
        return new Snapshot(captured, fingerprints, status);
    }

    private static IOException changed()
    {
        return new IOException("Project sources, Git state, or workspace scope changed during capture. Retry the snapshot.");
    }

    private static boolean sameManifest(List<Project> a, List<Project> b)
    {
        if (a.size() != b.size())
        {
            return false;
        }
        for (int position = 0; position < a.size(); position++)
        {
            Project left = a.get(position);
            Project right = b.get(position);
            if (!left.name().equals(right.name()) || !Objects.equals(left.head(), right.head())
                || !Objects.equals(left.branch(), right.branch()) || left.rawDiskComparison() != right.rawDiskComparison()
                || !Objects.equals(left.workTree(), right.workTree())
                || left.entries().size() != right.entries().size() || left.perimeter().size() != right.perimeter().size())
            {
                return false;
            }
            var leftEntries = left.allReviewEntries();
            var rightEntries = right.allReviewEntries();
            for (int entryIndex = 0; entryIndex < leftEntries.size(); entryIndex++)
            {
                Entry x = leftEntries.get(entryIndex);
                Entry y = rightEntries.get(entryIndex);
                if (!x.path().equals(y.path()) || !Objects.equals(id(x.head()), id(y.head()))
                    || !Objects.equals(id(x.index()), id(y.index())) || !Objects.equals(id(x.disk()), id(y.disk())))
                {
                    return false;
                }
            }
        }
        return true;
    }

    private static String id(Version version)
    {
        return version == null ? null : version.contentId();
    }

    static Map<String, String> fingerprints(List<Project> projects)
    {
        Map<String, String> result = new LinkedHashMap<>();
        for (Project project : projects)
        {
            MessageDigest digest = sha256();
            frame(digest, "edt-project-source-v1");
            frame(digest, project.name());
            List<Entry> entries = new ArrayList<>(project.entries());
            entries.sort(Comparator.comparing(Entry::path));
            for (Entry entry : entries)
            {
                if (entry.disk() != null)
                {
                    frame(digest, entry.path());
                    frame(digest, entry.disk().contentId());
                }
            }
            if (project.buildInputs() != null)
            {
                project.buildInputs().files().entrySet().stream().sorted(Map.Entry.comparingByKey())
                    .forEach(file -> {
                        frame(digest, file.getKey());
                        frame(digest, file.getValue().contentId());
                    });
            }
            result.put(project.name(), HexFormat.of().formatHex(digest.digest()));
        }
        return Map.copyOf(result);
    }

    private static MessageDigest sha256()
    {
        try
        {
            return MessageDigest.getInstance("SHA-256");
        }
        catch (NoSuchAlgorithmException e)
        {
            throw new IllegalStateException("SHA-256 is unavailable.", e);
        }
    }

    private static void frame(MessageDigest digest, String value)
    {
        byte[] data = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(data.length).array());
        digest.update(data);
    }

    private static ModelObservation model(Set<IProject> projects)
    {
        Display display = LaunchLifecycleUtils.workbenchDisplayOrNull();
        if (display == null || display.isDisposed())
        {
            return ModelObservation.UNKNOWN;
        }
        if (Display.getCurrent() == display)
        {
            return modelOnUiThread(projects);
        }
        CompletableFuture<ModelObservation> result = new CompletableFuture<>();
        try
        {
            display.asyncExec(() -> {
                if (!result.isDone())
                {
                    try
                    {
                        result.complete(modelOnUiThread(projects));
                    }
                    catch (RuntimeException e)
                    {
                        result.complete(ModelObservation.UNKNOWN);
                    }
                }
            });
            return result.get(2, TimeUnit.SECONDS);
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            return ModelObservation.UNKNOWN;
        }
        catch (ExecutionException | TimeoutException | RuntimeException e)
        {
            return ModelObservation.UNKNOWN;
        }
        finally
        {
            result.cancel(false);
        }
    }

    static void requireNoObservedDivergence(Access access) throws IOException
    {
        Set<IProject> projects = Set.copyOf(access.projects().stream().map(ScopedProject::project).toList());
        if (model(projects).dirty())
        {
            throw new IOException("The EDT model diverged. Resolve the divergence and capture a new snapshot.");
        }
    }

    private static ModelObservation modelOnUiThread(Set<IProject> projects)
    {
        for (IWorkbenchWindow window : PlatformUI.getWorkbench().getWorkbenchWindows())
        {
            for (IWorkbenchPage page : window.getPages())
            {
                if (hasDirtyEditor(projects, page.getDirtyEditors()))
                {
                    return new ModelObservation(true, Map.of());
                }
            }
        }
        // The workbench can attest to unsaved editors. It exposes no digest of the live serialized BM model.
        return ModelObservation.UNKNOWN;
    }

    static boolean hasDirtyEditor(Set<IProject> projects, IEditorPart[] editors)
    {
        for (IEditorPart editor : editors)
        {
            if (!editor.isDirty())
            {
                continue;
            }
            IEditorInput input = editor.getEditorInput();
            IResource resource = input == null ? null : input.getAdapter(IResource.class);
            if (resource == null || projects.contains(resource.getProject()))
            {
                return true;
            }
        }
        return false;
    }
}
