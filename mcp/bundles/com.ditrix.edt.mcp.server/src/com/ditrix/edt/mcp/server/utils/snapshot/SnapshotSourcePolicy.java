/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils.snapshot;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;

import com.ditrix.edt.mcp.server.protocol.McpProtocolHandler;
import com.ditrix.edt.mcp.server.transport.HttpTransport.AuthenticatedClient;
import com.ditrix.edt.mcp.server.utils.ProjectContext;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** The server-owned grant: an exact EDT name and local location, plus allowed source extensions. */
final class SnapshotSourcePolicy
{
    static final String POLICY_PROPERTY = "edt.mcp.snapshots.policy"; //$NON-NLS-1$
    static final String SOURCE_DIRECTORY = "src"; //$NON-NLS-1$
    private static final int MAX_PROJECTS = 16;
    private static final int MAX_POLICY_LENGTH = 65536;

    private SnapshotSourcePolicy()
    {
        // Utility class
    }

    static Access require(String projectName, List<String> included) throws IOException
    {
        AuthenticatedClient client = McpProtocolHandler.currentAuthenticatedClient();
        return require(client, System.getProperty(POLICY_PROPERTY), projectName, included);
    }

    static Access require(AuthenticatedClient client, String policyJson, String projectName, List<String> included)
        throws IOException
    {
        if (client == null || !client.isCurrent() || !System.getProperty("os.name", "").startsWith("Windows")) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {
            throw denied();
        }
        List<Grant> grants = grants(policyJson, projectName, included);
        List<ScopedProject> projects = new ArrayList<>();
        try
        {
            for (Grant grant : grants)
            {
                ProjectContext context = ProjectContext.of(grant.name());
                IProject project = context.project();
                if (!context.isOpen() || project.getLocation() == null
                    || !grant.location().equalsIgnoreCase(project.getLocation().toOSString())
                    || project.getFolder(SOURCE_DIRECTORY).isLinked(IResource.CHECK_ANCESTORS))
                {
                    throw denied();
                }
                Path location = Path.of(grant.location());
                Path sources = location.resolve(SOURCE_DIRECTORY);
                for (IProject other : ProjectContext.allProjects())
                {
                    if (!other.equals(project) && other.getLocation() != null)
                    {
                        Path otherLocation = other.getLocation().toFile().toPath();
                        if (otherLocation.equals(location) || otherLocation.startsWith(sources))
                        {
                            throw denied();
                        }
                    }
                }
                projects.add(new ScopedProject(project, grant, location));
            }
            return new Access(client, WindowsSourceTree.load(), List.copyOf(projects));
        }
        catch (LinkageError | RuntimeException e)
        {
            throw denied();
        }
    }

    static List<Grant> grants(String policy, String projectName, List<String> included) throws IOException
    {
        if (policy == null || policy.isBlank() || policy.length() > MAX_POLICY_LENGTH || projectName == null
            || included == null || included.size() >= MAX_PROJECTS)
        {
            throw denied();
        }
        List<String> names = new ArrayList<>(included.size() + 1);
        names.add(projectName);
        names.addAll(included);
        try
        {
            JsonObject configured = JsonParser.parseString(policy).getAsJsonObject();
            Set<String> distinct = new HashSet<>();
            List<Grant> result = new ArrayList<>();
            for (String name : names)
            {
                if (!WindowsSourceTree.isComponent(name) || !distinct.add(name.toLowerCase(Locale.ROOT)))
                {
                    throw denied();
                }
                JsonObject item = configured.getAsJsonObject(name);
                if (item == null)
                {
                    throw denied();
                }
                String location = item.getAsJsonPrimitive("location").getAsString(); //$NON-NLS-1$
                if (!WindowsSourceTree.isDrivePath(location) || location.length() <= 3 || location.length() > 4096)
                {
                    throw denied();
                }
                for (String component : location.substring(3).split("\\\\", -1)) //$NON-NLS-1$
                {
                    if (!WindowsSourceTree.isComponent(component))
                    {
                        throw denied();
                    }
                }
                Set<String> extensions = new HashSet<>();
                for (JsonElement value : item.getAsJsonArray("extensions")) //$NON-NLS-1$
                {
                    String extension = value.getAsString();
                    if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()
                        || !extension.matches("[a-zA-Z0-9]{1,16}")) //$NON-NLS-1$
                    {
                        throw denied();
                    }
                    extensions.add(extension.toLowerCase(Locale.ROOT));
                }
                if (extensions.isEmpty())
                {
                    throw denied();
                }
                result.add(new Grant(name, location, Set.copyOf(extensions)));
            }
            return List.copyOf(result);
        }
        catch (RuntimeException | LinkageError e)
        {
            throw denied();
        }
    }

    static IOException denied()
    {
        return new IOException("Snapshot access is unavailable for this caller or project scope."); //$NON-NLS-1$
    }

    record Grant(String name, String location, Set<String> extensions)
    {
        boolean permits(String projectRelative)
        {
            if (!projectRelative.startsWith(SOURCE_DIRECTORY + "/")) //$NON-NLS-1$
            {
                return false;
            }
            String[] parts = projectRelative.split("/", -1); //$NON-NLS-1$
            for (String part : parts)
            {
                if (!isSourceComponent(part))
                {
                    return false;
                }
            }
            String leaf = parts[parts.length - 1];
            int dot = leaf.lastIndexOf('.');
            return dot >= 1 && extensions.contains(leaf.substring(dot + 1).toLowerCase(Locale.ROOT));
        }

        private static boolean isSourceComponent(String part)
        {
            return WindowsSourceTree.isComponent(part) && part.charAt(0) != '.';
        }

        String projectPathInRepository(String sourcePrefix, String repositoryPath)
        {
            if (!repositoryPath.startsWith(sourcePrefix))
            {
                return null;
            }
            String relative = repositoryPath.substring(sourcePrefix.length());
            return permits(relative) ? relative : null;
        }
    }

    record ScopedProject(IProject project, Grant grant, Path location)
    {
        boolean isWorkspaceLink(String projectRelative, boolean directory)
        {
            IResource resource = directory ? project.getFolder(projectRelative) : project.getFile(projectRelative);
            return resource.isLinked(IResource.CHECK_ANCESTORS) || resource.isVirtual();
        }

        boolean permits(String projectRelative)
        {
            return grant.permits(projectRelative) && !isWorkspaceLink(projectRelative, false);
        }

        WindowsSourceTree.Directory open(WindowsSourceTree tree) throws IOException
        {
            return tree.openProject(grant.location());
        }
    }

    record Access(AuthenticatedClient client, WindowsSourceTree tree, List<ScopedProject> projects) { }
}
