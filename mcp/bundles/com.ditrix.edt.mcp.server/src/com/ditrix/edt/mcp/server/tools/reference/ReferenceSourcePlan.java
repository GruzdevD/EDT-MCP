/**
 * MCP Server for EDT
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.tools.reference;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.emf.common.util.URI;

import com._1c.g5.v8.bm.core.IBmCrossReference;
import com._1c.g5.v8.bm.core.IBmNamespace;
import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.core.IBmPlatformTransaction;
import com._1c.g5.v8.dt.core.filesystem.IQualifiedNameFilePathConverter;
import com._1c.g5.wiring.ServiceAccess;
import com.ditrix.edt.mcp.server.Activator;

/** Workspace file descriptors and a BM index join. Neither inventory step loads source contents. */
final class ReferenceSourcePlan
{
    record Address(String project, String path)
    {
        URI uri()
        {
            return URI.createPlatformResourceURI("/" + project + "/src/" + path, true);
        }
    }

    record Source(IFile file, Address address, boolean selected, long stamp, long modifiedMillis, long size)
    {
        boolean bsl()
        {
            return isBsl(file);
        }
    }

    record BmKey(IBmNamespace namespace, int resourceId) {}

    record BmPlan(Map<BmKey, Source> unique, Set<BmKey> ambiguous, Set<IBmNamespace> namespaces,
        Set<Address> planned, List<String> unavailable)
    {
        BmKey key(IBmCrossReference reference)
        {
            IBmNamespace namespace = reference.getNamespace();
            if (!namespaces.contains(namespace))
            {
                return null;
            }
            return new BmKey(namespace, namespace.asEngine().getObjectResourceId(reference.getObjectId()));
        }
    }

    private final List<Source> files;
    private final List<String> unavailable;

    private ReferenceSourcePlan(List<Source> files, List<String> unavailable)
    {
        this.files = List.copyOf(files);
        this.unavailable = List.copyOf(unavailable);
    }

    static ReferenceSourcePlan capture(List<IProject> projects, ReferenceSourceScope scope,
        BooleanSupplier stopped)
    {
        return capture(projects, scope, stopped, false);
    }

    static ReferenceSourcePlan captureBsl(List<IProject> projects, BooleanSupplier stopped)
    {
        return capture(projects, null, stopped, true);
    }

    private static boolean isBsl(IFile file)
    {
        return "bsl".equalsIgnoreCase(file.getFileExtension());
    }

    private static ReferenceSourcePlan capture(List<IProject> projects, ReferenceSourceScope scope,
        BooleanSupplier stopped, boolean bslOnly)
    {
        List<Source> files = new ArrayList<>();
        List<String> unavailable = new ArrayList<>();
        List<String> selectedPaths = scope == null ? List.of("") : scope.selectedPaths();
        for (IProject project : projects)
        {
            checkStopped(stopped);
            String name = project.getName();
            if (!project.isAccessible())
            {
                unavailable.add(name + "/src");
                continue;
            }
            var src = project.getFolder("src");
            if (!src.isAccessible())
            {
                unavailable.add(name + "/src");
                continue;
            }
            for (String selectedPath : selectedPaths)
            {
                checkStopped(stopped);
                boolean directory = selectedPath.isEmpty() || selectedPath.endsWith("/");
                var relativePath = org.eclipse.core.runtime.Path.fromPortableString(selectedPath).removeTrailingSeparator();
                IResource resource = selectedPath.isEmpty() ? src
                    : directory ? src.getFolder(relativePath) : src.getFile(relativePath);
                int depth = directory ? IResource.DEPTH_INFINITE : IResource.DEPTH_ZERO;
                // Workspace handles also compare local files that Eclipse has yet to discover.
                if (!resource.isSynchronized(depth))
                {
                    unavailable.add(name + "/src/" + selectedPath
                        + " differs from the physical " + (directory ? "directory" : "file"));
                }
            }
            try
            {
                src.accept(resource -> {
                    checkStopped(stopped);
                    if (resource instanceof IFile file && (!bslOnly || isBsl(file)))
                    {
                        String path = file.getProjectRelativePath().removeFirstSegments(1).toPortableString();
                        boolean selected = scope == null || scope.includes(name, path);
                        Source source = fileDescriptor(file, new Address(name, path), selected);
                        if (source != null)
                        {
                            files.add(source);
                        }
                        else if (selected)
                        {
                            unavailable.add(name + "/src/" + path);
                        }
                    }
                    return true;
                });
            }
            catch (CoreException e)
            {
                Activator.logError("Could not enumerate reference source files in " + name, e);
                unavailable.add(name + "/src");
            }
        }
        return new ReferenceSourcePlan(files, unavailable);
    }

    private static Source fileDescriptor(IFile file, Address address, boolean selected)
    {
        var location = file.getLocation();
        if (!file.isAccessible() || location == null || !file.isSynchronized(IResource.DEPTH_ZERO))
        {
            return null;
        }
        try
        {
            var stat = Files.readAttributes(Path.of(location.toOSString()), BasicFileAttributes.class);
            return stat.isRegularFile() ? new Source(file, address, selected, file.getModificationStamp(),
                stat.lastModifiedTime().toMillis(), stat.size()) : null;
        }
        catch (IOException e)
        {
            return null;
        }
    }

    BmPlan bind(Function<IProject, IBmNamespace> namespaceOf, IBmPlatformTransaction transaction,
        BooleanSupplier stopped)
    {
        IQualifiedNameFilePathConverter converter = ServiceAccess.get(IQualifiedNameFilePathConverter.class);
        return bind(file -> {
            try
            {
                var name = converter.getFqn(file);
                return name == null ? null : name.toString();
            }
            catch (IllegalArgumentException | AssertionError e)
            {
                // EDT's converter rejects unsupported physical path shapes.
                return null;
            }
        }, namespaceOf, transaction, stopped);
    }

    BmPlan bind(Function<IFile, String> nameOf, Function<IProject, IBmNamespace> namespaceOf,
        IBmPlatformTransaction transaction, BooleanSupplier stopped)
    {
        Map<BmKey, Source> unique = new HashMap<>();
        Set<BmKey> ambiguous = new HashSet<>();
        Set<BmKey> selectedAmbiguities = new HashSet<>();
        Set<IBmNamespace> namespaces = new HashSet<>();
        Set<Address> planned = new HashSet<>();
        List<String> missing = new ArrayList<>();
        for (Source source : files)
        {
            checkStopped(stopped);
            if (source.bsl())
            {
                continue;
            }
            IBmNamespace namespace = namespaceOf.apply(source.file().getProject());
            String fqn = namespace == null ? null : nameOf.apply(source.file());
            if (namespace != null)
            {
                namespaces.add(namespace);
            }
            long id = namespace == null || fqn == null ? IBmObject.BM_NULL_ID
                : transaction.getTopObjectIdByFqn(namespace, fqn);
            int resourceId = id == IBmObject.BM_NULL_ID ? 0 : namespace.asEngine().getObjectResourceId(id);
            if (resourceId <= 0)
            {
                if (source.selected())
                {
                    missing.add(source.address().project() + "/src/" + source.address().path());
                }
                continue;
            }
            BmKey key = new BmKey(namespace, resourceId);
            Source previous = unique.putIfAbsent(key, source);
            if (previous != null && !previous.address().equals(source.address()))
            {
                ambiguous.add(key);
                if (previous.selected() || source.selected())
                {
                    selectedAmbiguities.add(key);
                }
            }
        }
        for (BmKey key : ambiguous)
        {
            Source source = unique.remove(key);
            if (selectedAmbiguities.contains(key))
            {
                missing.add("Ambiguous BM resource " + key.resourceId() + " in " + source.address().project());
            }
        }
        for (Source source : unique.values())
        {
            if (source.selected())
            {
                planned.add(source.address());
            }
        }
        return new BmPlan(Map.copyOf(unique), Set.copyOf(ambiguous), Set.copyOf(namespaces),
            Set.copyOf(planned), List.copyOf(missing));
    }

    List<Source> files()
    {
        return files;
    }

    List<String> unavailable()
    {
        return unavailable;
    }

    boolean sameFiles(ReferenceSourcePlan other)
    {
        // Compare descriptor paths and stamps. EDT exposes no atomic model/index revision here.
        return Set.copyOf(files).equals(Set.copyOf(other.files))
            && Set.copyOf(unavailable).equals(Set.copyOf(other.unavailable));
    }

    private static void checkStopped(BooleanSupplier stopped)
    {
        if (stopped.getAsBoolean())
        {
            throw new OperationCanceledException();
        }
    }
}
