/**
 * MCP Server for EDT
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.tools.reference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IResourceVisitor;
import org.eclipse.core.runtime.Path;
import org.junit.After;
import org.junit.Test;

import com._1c.g5.v8.bm.core.IBmCrossReference;
import com._1c.g5.v8.bm.core.IBmEngine;
import com._1c.g5.v8.bm.core.IBmNamespace;
import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.core.IBmPlatformTransaction;

public class ReferenceSourcePlanTest
{
    private final List<java.nio.file.Path> temporaryFiles = new ArrayList<>();

    @After
    public void removeTemporaryFiles() throws Exception
    {
        for (var file : temporaryFiles)
        {
            Files.deleteIfExists(file);
        }
    }

    @Test
    public void capturesOnlyResourceDescriptorsAndJoinsByNamespace()
    {
        IProject base = project("Base", List.of("Config.mdo", "Root.bsl", "Other.mdo"));
        IProject extension = project("Extension", List.of("Config.mdo"));
        ReferenceSourceScope scope = ReferenceSourceScope.parse(Map.of(
            "projectNames", List.of("Base", "Extension"), "paths", List.of("Config.mdo", "Root.bsl")));
        var inventory = ReferenceSourcePlan.capture(List.of(base, extension), scope, () -> false);
        var baseNamespace = namespace();
        var extensionNamespace = namespace();
        var plan = inventory.bind(IFile::getName, project -> "Base".equals(project.getName()) ? baseNamespace : extensionNamespace,
            transaction(Map.of("Config.mdo", 5L, "Other.mdo", 6L)), () -> false);

        assertTrue(inventory.unavailable().isEmpty());
        assertEquals(4, inventory.files().size());
        assertEquals(2, plan.planned().size());
        assertEquals(new ReferenceSourcePlan.Address("Base", "Config.mdo"),
            plan.unique().get(plan.key(reference(baseNamespace, 5L))).address());
        assertEquals(new ReferenceSourcePlan.Address("Extension", "Config.mdo"),
            plan.unique().get(plan.key(reference(extensionNamespace, 5L))).address());
        assertFalse(plan.unique().get(plan.key(reference(baseNamespace, 6L))).selected());
        assertNull(plan.key(reference(namespace(), 5L)));
        assertNull(plan.unique().get(plan.key(reference(baseNamespace, 13L))));
    }

    @Test
    public void ambiguousAndMissingMappingsKeepSelectedResourcesUnavailable()
    {
        IProject base = project("Base", List.of("First.mdo", "Second.mdo", "Missing.mdo"));
        IProject closed = stub(IProject.class, (name, args) -> switch (name)
        {
            case "getName" -> "Closed";
            case "isAccessible" -> false;
            default -> unexpected(name);
        });
        var inventory = ReferenceSourcePlan.capture(List.of(base, closed), null, () -> false);
        var namespace = namespace();
        var plan = inventory.bind(IFile::getName, project -> namespace,
            transaction(Map.of("First.mdo", 2L, "Second.mdo", 2L)), () -> false);

        assertEquals(List.of("Closed/src"), inventory.unavailable());
        assertTrue(plan.planned().isEmpty());
        assertTrue(plan.unique().isEmpty());
        assertEquals(1, plan.ambiguous().size());
        assertTrue(plan.unavailable().contains("Base/src/Missing.mdo"));
        assertNull(plan.unique().get(plan.key(reference(namespace, 2L))));
    }

    @Test
    public void collisionsOutsideSelectedFilesRemainExcludedWithoutReducingCoverage()
    {
        IProject base = project("Base", List.of("First.mdo", "Second.mdo", "Selected.mdo"));
        var scope = ReferenceSourceScope.parse(Map.of("projectNames", List.of("Base"),
            "paths", List.of("Selected.mdo")));
        var inventory = ReferenceSourcePlan.capture(List.of(base), scope, () -> false);
        var namespace = namespace();
        var plan = inventory.bind(IFile::getName, project -> namespace,
            transaction(Map.of("First.mdo", 2L, "Second.mdo", 2L, "Selected.mdo", 3L)), () -> false);

        assertTrue(plan.unavailable().isEmpty());
        assertEquals(Set.of(new ReferenceSourcePlan.Address("Base", "Selected.mdo")), plan.planned());
        assertTrue(plan.ambiguous().contains(plan.key(reference(namespace, 2L))));
    }

    @Test
    public void outOfSyncDirectoryBlocksCoverageButDoesNotInvalidateAnIndependentExactFile()
    {
        IProject base = project("Base", List.of("Known.bsl"), false);
        var wholeProject = ReferenceSourcePlan.captureBsl(List.of(base), () -> false);
        assertEquals(List.of("Base/src/ differs from the physical directory"), wholeProject.unavailable());

        var exact = ReferenceSourceScope.parse(Map.of("projectNames", List.of("Base"), "paths", List.of("Known.bsl")));
        assertTrue(ReferenceSourcePlan.capture(List.of(base), exact, () -> false).unavailable().isEmpty());
    }

    @Test
    public void checksExactSelectorInOtherProjectsEvenWhenTheirWorkspaceInventoryHasNoMatch()
    {
        IProject base = project("Base", List.of("Known.bsl"));
        IProject extension = project("Extension", List.of(), true, false);
        var exact = ReferenceSourceScope.parse(Map.of("projectNames", List.of("Base", "Extension"),
            "paths", List.of("Known.bsl")));
        assertEquals(List.of("Extension/src/Known.bsl differs from the physical file"),
            ReferenceSourcePlan.capture(List.of(base, extension), exact, () -> false).unavailable());
    }

    private IProject project(String projectName, List<String> paths)
    {
        return project(projectName, paths, true);
    }

    private IProject project(String projectName, List<String> paths, boolean synchronizedTree)
    {
        return project(projectName, paths, synchronizedTree, true);
    }

    private IProject project(String projectName, List<String> paths, boolean synchronizedTree,
        boolean synchronizedExactFile)
    {
        return stub(IProject.class, (name, args) -> switch (name)
        {
            case "getName" -> projectName;
            case "isAccessible" -> true;
            case "getFolder" -> stub(IFolder.class, (folderMethod, folderArgs) -> switch (folderMethod)
            {
                case "isAccessible" -> true;
                case "isSynchronized" ->
                {
                    assertEquals(IResource.DEPTH_INFINITE, folderArgs[0]);
                    yield synchronizedTree;
                }
                case "getFile" -> stub(IFile.class, (fileMethod, fileArgs) -> switch (fileMethod)
                {
                    case "isSynchronized" ->
                    {
                        assertEquals(IResource.DEPTH_ZERO, fileArgs[0]);
                        yield synchronizedExactFile;
                    }
                    default -> unexpected(fileMethod);
                });
                case "accept" ->
                {
                    var visitor = (IResourceVisitor)folderArgs[0];
                    for (String path : paths)
                    {
                        try
                        {
                            visitor.visit(file(projectName, path));
                        }
                        catch (Exception e)
                        {
                            throw new AssertionError(e);
                        }
                    }
                    yield null;
                }
                default -> unexpected(folderMethod);
            });
            default -> unexpected(name);
        });
    }

    private IFile file(String projectName, String fileName) throws Exception
    {
        var location = Files.createTempFile("reference-source-", ".fixture");
        temporaryFiles.add(location);
        return stub(IFile.class, (method, args) -> switch (method)
        {
            case "isAccessible", "isSynchronized" -> true;
            case "getName" -> fileName;
            case "getFileExtension" -> Path.fromPortableString(fileName).getFileExtension();
            case "getProjectRelativePath" -> Path.fromPortableString("src/" + fileName);
            case "getLocation" -> Path.fromOSString(location.toAbsolutePath().toString());
            case "getModificationStamp" -> 10L;
            case "getProject" -> project(projectName, List.of());
            default -> unexpected(method);
        });
    }

    private static IBmNamespace namespace()
    {
        return stub(IBmNamespace.class, (method, args) -> "asEngine".equals(method)
            ? stub(IBmEngine.class, (engineMethod, engineArgs) -> "getObjectResourceId".equals(engineMethod)
                ? ((Long)engineArgs[0]).intValue() : unexpected(engineMethod))
            : unexpected(method));
    }

    private static IBmPlatformTransaction transaction(Map<String, Long> ids)
    {
        return stub(IBmPlatformTransaction.class, (method, args) -> "getTopObjectIdByFqn".equals(method)
            ? ids.getOrDefault(args[1], IBmObject.BM_NULL_ID) : unexpected(method));
    }

    private static IBmCrossReference reference(IBmNamespace namespace, long id)
    {
        return stub(IBmCrossReference.class, (method, args) -> switch (method)
        {
            case "getNamespace" -> namespace;
            case "getObjectId" -> id;
            default -> unexpected(method);
        });
    }

    static <T> T stub(Class<T> type, BiFunction<String, Object[], Object> action)
    {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
            (proxy, method, args) -> switch (method.getName())
            {
                case "equals" -> proxy == args[0];
                case "hashCode" -> System.identityHashCode(proxy);
                case "toString" -> type.getSimpleName();
                default -> action.apply(method.getName(), args);
            }));
    }

    static Object unexpected(String method)
    {
        throw new AssertionError("Source inventory touched " + method);
    }
}
