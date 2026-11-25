/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.utils.snapshot;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.jar.Manifest;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.helpers.DefaultHandler;

import com.ditrix.edt.mcp.server.utils.MetadataTypeUtils.MetadataTypeInfo;
import com.ditrix.edt.mcp.server.utils.snapshot.ProjectSourceManifest.Project;
import com.ditrix.edt.mcp.server.utils.snapshot.ProjectSourceManifest.Version;
import com.ditrix.edt.mcp.server.utils.snapshot.SnapshotSourcePolicy.ScopedProject;
import com.ditrix.edt.mcp.server.utils.snapshot.WindowsSourceTree.Directory;

/** The non-source files understood by the isolated EDT builder. */
record SnapshotBuildInputs(String parent, String runtime, Map<String, Version> files)
{
    private static final int MAX_DESCRIPTOR = 64 * 1024;
    private static final String PROJECT = ".project";
    private static final String MANIFEST = "DT-INF/PROJECT.PMF";
    private static final String ENCODING = "org.eclipse.core.resources.prefs";
    private static final String XTEXT = "org.eclipse.xtext.ui.shared.xtextNature";
    private static final String BASE = "com._1c.g5.v8.dt.core.V8ConfigurationNature";
    private static final String EXTENSION = "com._1c.g5.v8.dt.core.V8ExtensionNature";
    private static final String CONFIGURATION = "src/Configuration/Configuration.mdo";
    private static final String METADATA_NAMESPACE = "http://g5.1c.ru/v8/dt/metadata/mdclass";
    private static final Pattern METADATA_NAME = Pattern.compile("[\\p{L}_][\\p{L}\\p{N}_]*");
    private static final Map<String, MetadataTypeInfo> COLLECTIONS = Arrays.stream(MetadataTypeInfo.values())
        .filter(type -> !type.isStandalone())
        .collect(Collectors.toUnmodifiableMap(MetadataTypeInfo::getConfigReferenceName, type -> type));

    SnapshotBuildInputs
    {
        files = Map.copyOf(files);
    }

    static SnapshotBuildInputs read(ScopedProject project, Directory root) throws IOException
    {
        if (project.isWorkspaceLink(PROJECT, false) || project.isWorkspaceLink(MANIFEST, false)
            || project.isWorkspaceLink(".settings/" + ENCODING, false))
        {
            throw unsupported();
        }
        var files = new LinkedHashMap<String, Version>();
        files.put(PROJECT, new Version(root.read(PROJECT, MAX_DESCRIPTOR)));
        try (Directory directory = root.child("DT-INF"))
        {
            if (directory.entries(2).size() != 1)
            {
                throw unsupported();
            }
            files.put(MANIFEST, new Version(directory.read("PROJECT.PMF", MAX_DESCRIPTOR)));
        }
        try (Directory settings = root.childIfPresent(".settings"))
        {
            if (settings != null)
            {
                byte[] encoding = settings.readIfPresent(ENCODING, MAX_DESCRIPTOR);
                if (encoding != null)
                {
                    files.put(".settings/" + ENCODING, new Version(encoding));
                }
            }
        }
        return validate(project.grant().name(), files);
    }

    static SnapshotBuildInputs validate(String name, Map<String, Version> files) throws IOException
    {
        try
        {
            var manifest = new Manifest(new ByteArrayInputStream(files.get(MANIFEST).bytes()));
            var attributes = manifest.getMainAttributes();
            String parent = attributes.getValue("Base-Project");
            String runtime = attributes.getValue("Runtime-Version");
            if (!manifest.getEntries().isEmpty() || attributes.size() != (parent == null ? 2 : 3)
                || !"1.0".equals(attributes.getValue("Manifest-Version"))
                || runtime == null || !runtime.matches("\\d+\\.\\d+\\.\\d+(\\.\\d+)?")
                || parent != null && !WindowsSourceTree.isComponent(parent))
            {
                throw unsupported();
            }
            Element root = parse(newParser(), files.get(PROJECT));
            String nature = parent == null ? BASE : EXTENSION;
            if (!"projectDescription".equals(root.getTagName())
                || !Set.of("name", "comment", "projects", "buildSpec", "natures").containsAll(childTags(root))
                || !name.equals(singleText(root, "name")) || !singleText(root, "projects").isBlank())
            {
                throw unsupported();
            }
            Element natures = singleElement(root, "natures");
            if (!childTags(natures).equals(List.of("nature", "nature"))
                || !Set.of(nature, XTEXT).equals(Set.of(
                    natures.getElementsByTagName("nature").item(0).getTextContent().trim(),
                    natures.getElementsByTagName("nature").item(1).getTextContent().trim())))
            {
                throw unsupported();
            }
            Element build = singleElement(root, "buildSpec");
            Element command = singleElement(build, "buildCommand");
            if (!childTags(build).equals(List.of("buildCommand"))
                || !childTags(command).equals(List.of("name", "arguments"))
                || !"org.eclipse.xtext.ui.shared.xtextBuilder".equals(singleText(command, "name"))
                || !childTags(singleElement(command, "arguments")).isEmpty())
            {
                throw unsupported();
            }
            return new SnapshotBuildInputs(parent, runtime, files);
        }
        catch (SAXException | ParserConfigurationException | RuntimeException e)
        {
            throw unsupported();
        }
    }

    static void validateScope(List<Project> projects) throws IOException
    {
        Project base = projects.get(0);
        if (base.buildInputs().parent() != null)
        {
            throw unsupported();
        }
        for (Project extension : projects.subList(1, projects.size()))
        {
            if (!base.name().equals(extension.buildInputs().parent())
                || !base.buildInputs().runtime().equals(extension.buildInputs().runtime()))
            {
                throw unsupported();
            }
        }
        try
        {
            DocumentBuilder parser = newParser();
            for (Project project : projects)
            {
                validateConfiguration(project, parser);
            }
        }
        catch (SAXException | ParserConfigurationException | RuntimeException e)
        {
            throw unsupported();
        }
    }

    private static void validateConfiguration(Project project, DocumentBuilder parser) throws IOException, SAXException
    {
        Map<String, Version> disk = new HashMap<>();
        for (var entry : project.entries())
        {
            if (entry.disk() != null && entry.path().endsWith(".mdo"))
            {
                disk.put(entry.path(), entry.disk());
            }
        }
        Element configuration = parse(parser, disk.get(CONFIGURATION));
        requireMetadataRoot(configuration, "Configuration");
        for (Node node = configuration.getFirstChild(); node != null; node = node.getNextSibling())
        {
            if (!(node instanceof Element member) || member.getNamespaceURI() != null)
            {
                continue;
            }
            MetadataTypeInfo type = COLLECTIONS.get(member.getTagName());
            if (type != null)
            {
                validateDeclaration(member, type, disk, parser);
            }
        }
    }

    private static void validateDeclaration(Element member, MetadataTypeInfo type, Map<String, Version> disk,
        DocumentBuilder parser) throws IOException, SAXException
    {
        if (!childTags(member).isEmpty())
        {
            // Some configuration objects, such as languages, are serialized in Configuration.mdo.
            if (type == MetadataTypeInfo.SUBSYSTEM)
            {
                // EDT stores every subsystem in its own file, including nested subsystems.
                throw unsupported();
            }
            requireMetadataIdentity(member);
            return;
        }
        String prefix = type.getEnglishSingular() + ".";
        String reference = member.getTextContent().trim();
        if (!reference.startsWith(prefix))
        {
            throw unsupported();
        }
        String name = reference.substring(prefix.length());
        String directory = type.getDirectoryName() == null ? type.getEnglishPlural() : type.getDirectoryName();
        validateMetadataFile("src/" + directory, name, type, disk, parser);
    }

    private static void validateMetadataFile(String collection, String name, MetadataTypeInfo type,
        Map<String, Version> disk, DocumentBuilder parser) throws IOException, SAXException
    {
        if (!METADATA_NAME.matcher(name).matches())
        {
            throw unsupported();
        }
        String directory = collection + "/" + name;
        // A descriptor may be declared only once. This also bounds checks to the retained files.
        Element target = parse(parser, disk.remove(directory + "/" + name + ".mdo"));
        if (!name.equals(requireMetadataRoot(target, type.getEnglishSingular())))
        {
            throw unsupported();
        }
        if (type == MetadataTypeInfo.SUBSYSTEM)
        {
            validateNestedSubsystems(target, directory + "/Subsystems", disk, parser);
        }
    }

    private static void validateNestedSubsystems(Element parent, String collection, Map<String, Version> disk,
        DocumentBuilder parser) throws IOException, SAXException
    {
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling())
        {
            if (node instanceof Element child && "subsystems".equals(child.getTagName())
                && child.getNamespaceURI() == null)
            {
                String name = child.getTextContent().trim();
                if (!childTags(child).isEmpty())
                {
                    throw unsupported();
                }
                validateMetadataFile(collection, name, MetadataTypeInfo.SUBSYSTEM, disk, parser);
            }
        }
    }

    private static String requireMetadataRoot(Element element, String type) throws IOException
    {
        if (!type.equals(element.getLocalName()) || !METADATA_NAMESPACE.equals(element.getNamespaceURI()))
        {
            throw unsupported();
        }
        return requireMetadataIdentity(element);
    }

    private static String requireMetadataIdentity(Element element) throws IOException
    {
        String uuid = element.getAttribute("uuid");
        String name = singleText(element, "name");
        if (uuid.length() != 36 || !UUID.fromString(uuid).toString().equalsIgnoreCase(uuid)
            || !METADATA_NAME.matcher(name).matches())
        {
            throw unsupported();
        }
        return name;
    }

    private static Element parse(DocumentBuilder parser, Version version) throws IOException, SAXException
    {
        if (version == null)
        {
            throw unsupported();
        }
        return parser.parse(new ByteArrayInputStream(version.bytes())).getDocumentElement();
    }

    private static DocumentBuilder newParser() throws ParserConfigurationException
    {
        var factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        DocumentBuilder parser = factory.newDocumentBuilder();
        parser.setErrorHandler(new DefaultHandler()
        {
            @Override
            public void error(SAXParseException error) throws SAXException
            {
                throw error;
            }

            @Override
            public void fatalError(SAXParseException error) throws SAXException
            {
                throw error;
            }
        });
        return parser;
    }

    private static List<String> childTags(Element element)
    {
        var result = new ArrayList<String>();
        for (Node child = element.getFirstChild(); child != null; child = child.getNextSibling())
        {
            if (child instanceof Element tag)
            {
                result.add(tag.getTagName());
            }
        }
        return result;
    }

    private static Element singleElement(Element parent, String name) throws IOException
    {
        Element match = null;
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling())
        {
            if (node instanceof Element element && name.equals(element.getTagName()))
            {
                if (match != null)
                {
                    throw unsupported();
                }
                match = element;
            }
        }
        if (match == null)
        {
            throw unsupported();
        }
        return match;
    }

    private static String singleText(Element parent, String name) throws IOException
    {
        Element value = singleElement(parent, name);
        if (!childTags(value).isEmpty())
        {
            throw unsupported();
        }
        return value.getTextContent().trim();
    }

    static IOException unsupported()
    {
        return new IOException("Snapshot inputs cannot form an isolated EDT build. Check the project descriptors, "
            + "Configuration.mdo and its declared metadata files, extension dependencies, and permitted source files.");
    }
}
