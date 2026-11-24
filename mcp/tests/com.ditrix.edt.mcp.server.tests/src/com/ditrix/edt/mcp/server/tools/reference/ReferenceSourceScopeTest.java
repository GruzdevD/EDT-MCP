/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.reference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.junit.Test;

import com.ditrix.edt.mcp.server.protocol.GsonProvider;

public class ReferenceSourceScopeTest
{
    @Test
    public void testWholeProjectIncludesRootFilesButNotOtherProjects()
    {
        ReferenceSourceScope scope = parse("{\"projectNames\":[\"Base\"]}");
        assertTrue(scope.includes("Base", "Root.bsl"));
        assertTrue(scope.includes("Base", "Configuration/Configuration.mdo"));
        assertFalse(scope.includes("Extension", "Root.bsl"));
    }

    @Test
    public void testOverlappingExactAndDirectorySelectorsFormAUnion()
    {
        ReferenceSourceScope scope = parse("{\"projectNames\":[\"Base\",\"Extension\"],\"paths\":["
            + "\"CommonModules/\",\"CommonModules/A/Module.bsl\",\"Root.bsl\",\"Root.bsl\"]}");
        assertTrue(scope.includes("Base", "CommonModules/A/Module.bsl"));
        assertTrue(scope.includes("Extension", "CommonModules/B/Module.bsl"));
        assertTrue(scope.includes("Extension", "Root.bsl"));
        assertFalse(scope.includes("Base", "CommonModulesExtra/A/Module.bsl"));
        assertFalse(scope.includes("Base", "CommonModules"));
        assertFalse(scope.includes("Base", "Sub/Root.bsl"));
        assertFalse(scope.includes("Base", "Root.bsl.bak"));
        long hits = List.of("CommonModules/A/Module.bsl", "Root.bsl", "Root.bsl.bak").stream()
            .filter(path -> scope.includes("Base", path)).count();
        assertEquals(2, hits);
    }

    @Test
    public void testMalformedScopesAreRejectedNotNormalized()
    {
        for (String json : List.of("null", "[]", "{}", "{\"projectNames\":[]}",
            "{\"projectNames\":[null]}", "{\"projectNames\":[true]}", "{\"projectNames\":[\"\"]}",
            "{\"projectNames\":[\"a/b\"]}", "{\"projectNames\":[\"Base\"],\"Paths\":[]}",
            "{\"projectNames\":[\"Base\"],\"paths\":null}", "{\"projectNames\":[\"Base\"],\"paths\":[]}"))
        {
            assertTrue(json, assertThrows(IllegalArgumentException.class, () -> parse(json))
                .getMessage().contains("sourceScope"));
        }
        for (String selector : List.of("", "/", "/root.bsl", "C:/root.bsl", "a\\b.bsl", "./a.bsl",
            "a/../b.bsl", "a//", "a//b.bsl", "a/ /b.bsl", " a.bsl"))
        {
            assertTrue(selector, assertThrows(IllegalArgumentException.class,
                () -> ReferenceSourceScope.parse(Map.of("projectNames", List.of("Base"), "paths", List.of(selector))))
                    .getMessage().contains("sourceScope.paths[0]"));
        }
    }

    @Test
    public void testSelectorsMayResolveInDifferentProjectsWithoutTraversal()
    {
        List<String> baseLookups = new ArrayList<>();
        List<String> extensionLookups = new ArrayList<>();
        IProject baseProject = project(src(Map.of("CommonModules", IResource.FOLDER), baseLookups));
        IProject extensionProject = project(src(Map.of("Root.bsl", IResource.FILE), extensionLookups));
        ReferenceSourceScope scope = parse("{\"projectNames\":[\"Base\",\"Extension\",\"Base\"],"
            + "\"paths\":[\"CommonModules/\",\"Root.bsl\",\"Root.bsl\"]}");

        List<String> lookups = new ArrayList<>();
        assertEquals(List.of(baseProject, extensionProject), scope.resolveProjects(name -> {
            lookups.add(name);
            return "Base".equals(name) ? baseProject : extensionProject;
        }));
        assertEquals(List.of("Base", "Extension"), lookups);
        assertEquals(List.of("CommonModules", "Root.bsl"), baseLookups);
        assertEquals(List.of("Root.bsl"), extensionLookups);
    }

    @Test
    public void testUnknownProjectAndNonexistentOrWrongKindSelectorAreRejectedWithoutTraversal()
    {
        List<String> lookups = new ArrayList<>();
        IProject known = project(src(Map.of("CommonModules", IResource.FOLDER), lookups));
        IProject unknown = stub(IProject.class, (method, args) -> "exists".equals(method) ? false : unexpected(method));
        ReferenceSourceScope unknownProject = parse("{\"projectNames\":[\"Known\",\"Typo\"],\"paths\":[\"Root.bsl\"]}");
        assertTrue(assertThrows(IllegalArgumentException.class,
            () -> unknownProject.resolveProjects(name -> "Known".equals(name) ? known : unknown))
                .getMessage().contains("Typo"));
        assertTrue(lookups.isEmpty());

        for (String path : List.of("Missing.bsl", "CommonModules"))
        {
            ReferenceSourceScope scope = ReferenceSourceScope.parse(Map.of(
                "projectNames", List.of("Known"), "paths", List.of(path)));
            assertTrue(assertThrows(IllegalArgumentException.class, () -> scope.resolveProjects(name -> known))
                .getMessage().contains(path));
        }
        assertEquals(List.of("Missing.bsl", "CommonModules"), lookups);
    }

    private static IProject project(IFolder src)
    {
        return stub(IProject.class, (method, args) -> switch (method)
        {
            case "exists", "isAccessible" -> true;
            case "getFolder" -> "src".equals(args[0]) ? src : unexpected(method);
            default -> unexpected(method);
        });
    }

    private static IFolder src(Map<String, Integer> members, List<String> lookups)
    {
        return stub(IFolder.class, (method, args) -> {
            if (!"findMember".equals(method) || !(args[0] instanceof String path))
            {
                return unexpected(method);
            }
            lookups.add(path);
            Integer type = members.get(path);
            return type == null ? null : stub(IResource.class,
                (name, unused) -> "getType".equals(name) ? type : unexpected(name));
        });
    }

    private static Object unexpected(String method)
    {
        throw new AssertionError("Selector resolution must not call " + method);
    }

    private static <T> T stub(Class<T> type, BiFunction<String, Object[], Object> call)
    {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type},
            (proxy, method, args) -> switch (method.getName())
            {
                case "equals" -> proxy == args[0];
                case "hashCode" -> System.identityHashCode(proxy);
                case "toString" -> type.getSimpleName() + " stub";
                default -> call.apply(method.getName(), args);
            }));
    }

    private static ReferenceSourceScope parse(String json)
    {
        return ReferenceSourceScope.parse(GsonProvider.fromJson(json, Object.class));
    }
}
