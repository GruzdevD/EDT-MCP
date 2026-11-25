/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.reference;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;

import com.ditrix.edt.mcp.server.utils.ProjectContext;

/** The explicit source selection, independent of the project that owns the target. */
public final class ReferenceSourceScope
{
    private static final String KEY = "sourceScope"; //$NON-NLS-1$
    private static final String PROJECT_NAMES = "projectNames"; //$NON-NLS-1$
    private static final String PATHS = "paths"; //$NON-NLS-1$
    private static final String SRC = "src"; //$NON-NLS-1$

    private final List<String> projectNames;
    private final List<String> paths;

    record ResolvedSelection(List<IProject> projects, Map<IProject, Set<String>> matches) {}

    private ReferenceSourceScope(List<String> projectNames, List<String> paths)
    {
        this.projectNames = projectNames;
        this.paths = paths;
    }

    /** @return the JSON Schema property for this selection */
    public static Map<String, Object> inputSchema()
    {
        return Map.of("type", "object", "additionalProperties", false, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "description", "Source projects and optional selectors relative to src/.", //$NON-NLS-1$ //$NON-NLS-2$
            "required", List.of(PROJECT_NAMES), "properties", Map.of( //$NON-NLS-1$ //$NON-NLS-2$
                PROJECT_NAMES, stringListSchema("Names of EDT source projects, independent of projectName."), //$NON-NLS-1$
                PATHS, stringListSchema("Omit for whole src/ trees. Use 'CommonModules/' for a subtree " //$NON-NLS-1$
                    + "or 'Configuration/Configuration.mdo' for an exact file."))); //$NON-NLS-1$
    }

    private static Map<String, Object> stringListSchema(String description)
    {
        return Map.of("type", "array", "minItems", 1, "description", description, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            "items", Map.of("type", "string", "minLength", 1)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
    }

    /**
     * Validate a supplied scope without touching the workspace. Omission is handled by the caller.
     *
     * @param value the original JSON value, including explicit null
     * @return the validated selection
     * @throws IllegalArgumentException for malformed input
     */
    public static ReferenceSourceScope parse(Object value)
    {
        if (!(value instanceof Map<?, ?> scope))
        {
            throw invalid("must be an object with projectNames, or be omitted."); //$NON-NLS-1$
        }
        for (Object key : scope.keySet())
        {
            if (!PROJECT_NAMES.equals(key) && !PATHS.equals(key))
            {
                throw invalid("has unknown property '" + key + "'; use projectNames and optional paths."); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }
        return new ReferenceSourceScope(readList(scope.get(PROJECT_NAMES), PROJECT_NAMES),
            scope.containsKey(PATHS) ? readList(scope.get(PATHS), PATHS) : List.of());
    }

    private static List<String> readList(Object value, String key)
    {
        if (!(value instanceof List<?> list) || list.isEmpty())
        {
            throw invalid("." + key + " must be a nonempty array of strings."); //$NON-NLS-1$ //$NON-NLS-2$
        }
        boolean isPath = PATHS.equals(key);
        Set<String> validated = new LinkedHashSet<>();
        for (int i = 0; i < list.size(); i++)
        {
            if (!(list.get(i) instanceof String text) || !validSelector(text, isPath))
            {
                String expected = isPath
                    ? "a path relative to src/ using '/', with no empty, '.' or '..' segments" //$NON-NLS-1$
                    : "an exact EDT project name from list_projects"; //$NON-NLS-1$
                throw invalid("." + key + "[" + i + "] must be " + expected + "."); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            }
            validated.add(text);
        }
        return List.copyOf(validated);
    }

    private static boolean validSelector(String value, boolean isPath)
    {
        if (value.indexOf('\\') >= 0 || value.indexOf(':') >= 0
            || (!isPath && value.indexOf('/') >= 0) || value.codePoints().anyMatch(Character::isISOControl))
        {
            return false;
        }
        for (String segment : withoutDirectorySuffix(value).split("/", -1)) //$NON-NLS-1$
        {
            if (segment.isBlank() || !segment.equals(segment.strip())
                || ".".equals(segment) || "..".equals(segment)) //$NON-NLS-1$ //$NON-NLS-2$
            {
                return false;
            }
        }
        return true;
    }

    private static IllegalArgumentException invalid(String detail)
    {
        return new IllegalArgumentException(KEY + (detail.startsWith(".") ? "" : " ") + detail); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    private static boolean directory(String selector)
    {
        return selector.endsWith("/"); //$NON-NLS-1$
    }

    private static String withoutDirectorySuffix(String selector)
    {
        return directory(selector) ? selector.substring(0, selector.length() - 1) : selector;
    }

    /** @return whether the given canonical src-relative file is selected */
    public boolean includes(String projectName, String path)
    {
        return projectNames.contains(projectName) && (paths.isEmpty() || paths.stream().anyMatch(
            selector -> directory(selector) ? path.startsWith(selector) : path.equals(selector)));
    }

    Map<String, Object> selection()
    {
        return paths.isEmpty() ? Map.of(PROJECT_NAMES, projectNames)
            : Map.of(PROJECT_NAMES, projectNames, PATHS, paths);
    }

    List<String> selectedPaths()
    {
        return paths.isEmpty() ? List.of("") : paths;
    }

    ResolvedSelection resolve(Runnable check)
    {
        return resolve(name -> ProjectContext.of(name).project(), check);
    }

    ResolvedSelection resolve(Function<String, IProject> lookup, Runnable check)
    {
        List<IProject> projects = new ArrayList<>();
        for (String name : projectNames)
        {
            check.run();
            IProject project = lookup.apply(name);
            if (project == null || !project.exists())
            {
                throw invalid("." + PROJECT_NAMES + ": " + ProjectContext.notFoundMessage(name)); //$NON-NLS-1$ //$NON-NLS-2$
            }
            projects.add(project);
        }
        var matches = selectorMatches(projects, check);
        boolean allAccessible = matches.size() == projects.size();
        for (String selector : paths)
        {
            check.run();
            if (allAccessible && matches.values().stream().noneMatch(selectors -> selectors.contains(selector)))
            {
                String kind = directory(selector) ? "directory" : "file"; //$NON-NLS-1$ //$NON-NLS-2$
                throw invalid("." + PATHS + ": '" + selector + "' does not name a " + kind //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    + " under src/ in any selected project. Check the path and trailing slash."); //$NON-NLS-1$
            }
        }
        return new ResolvedSelection(List.copyOf(projects), matches);
    }

    Map<IProject, Set<String>> selectorMatches(List<IProject> projects, Runnable check)
    {
        Map<IProject, Set<String>> matches = new LinkedHashMap<>();
        for (IProject project : projects)
        {
            check.run();
            // A closed project cannot prove absence. The worker reports unavailable coverage for it.
            if (paths.isEmpty() || !project.isAccessible())
            {
                continue;
            }
            IFolder src = project.getFolder(SRC);
            Set<String> projectSelectors = new LinkedHashSet<>();
            for (String selector : paths)
            {
                check.run();
                IResource resource = src.findMember(withoutDirectorySuffix(selector));
                if (resource != null
                    && resource.getType() == (directory(selector) ? IResource.FOLDER : IResource.FILE))
                {
                    projectSelectors.add(selector);
                }
            }
            matches.put(project, Set.copyOf(projectSelectors));
        }
        return Map.copyOf(matches);
    }
}
