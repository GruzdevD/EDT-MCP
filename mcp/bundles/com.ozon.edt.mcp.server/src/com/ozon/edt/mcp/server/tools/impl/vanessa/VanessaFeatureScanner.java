/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 *
 * edt-mcp-vanessa: locate and lightly parse Vanessa Automation gherkin features.
 */

package com.ozon.edt.mcp.server.tools.impl.vanessa;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * File-level helpers for introspection of Vanessa Automation {@code .feature}
 * files: recursive discovery under a directory, and a deliberately light,
 * line-oriented gherkin parse (Feature:/Scenario:/tags/steps) — enough to
 * feed {@code vanessa_list_features} / {@code vanessa_get_feature} without
 * pulling in a full gherkin parser dependency.
 */
final class VanessaFeatureScanner
{
    private VanessaFeatureScanner()
    {
        // Utility class
    }

    /** Recursively finds {@code *.feature} files under {@code root}, sorted. */
    static List<Path> findFeatures(Path root) throws IOException
    {
        List<Path> out = new ArrayList<>();
        if (root == null || !Files.isDirectory(root))
        {
            return out;
        }
        try (Stream<Path> s = Files.walk(root))
        {
            s.filter(Files::isRegularFile)
                .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".feature")) //$NON-NLS-1$
                .sorted()
                .forEach(out::add);
        }
        return out;
    }

    /**
     * Extracts only the feature headline ({@code Feature} name + feature-level
     * tags) from a {@code .feature} file — cheap for a list view, no scenario
     * parsing.
     *
     * @param path the feature file
     * @return {@code {feature, tags[]}}
     * @throws IOException if the file cannot be read
     */
    static Map<String, Object> header(Path path) throws IOException
    {
        Map<String, Object> out = new LinkedHashMap<>();
        List<String> tags = new ArrayList<>();
        String feature = null;
        for (String raw : Files.readAllLines(path, StandardCharsets.UTF_8))
        {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) //$NON-NLS-1$
            {
                continue;
            }
            if (line.startsWith("@")) //$NON-NLS-1$
            {
                addTags(tags, line);
            }
            else if (isFeature(line))
            {
                feature = line.substring(line.indexOf(':') + 1).trim();
            }
            else if (isScenario(line))
            {
                break; // feature headline is over
            }
        }
        out.put("feature", feature == null ? "" : feature); //$NON-NLS-1$ //$NON-NLS-2$
        out.put("tags", tags);
        return out;
    }

    /**
     * Lightly parses a {@code .feature} file into a structure consumable as JSON.
     *
     * @param path the feature file
     * @return {@code {feature, tags[], scenarios[]}} where each scenario is
     *         {@code {name, tags[], steps[]}} (may be empty)
     * @throws IOException if the file cannot be read
     */
    static Map<String, Object> parse(Path path) throws IOException
    {
        Map<String, Object> out = new LinkedHashMap<>();
        String featureName = null;
        List<String> featureTags = new ArrayList<>();
        List<Map<String, Object>> scenarios = new ArrayList<>();

        // Accumulated tags awaiting their owner (feature block or next scenario).
        List<String> pendingTags = new ArrayList<>();
        List<String> currentSteps = null;

        for (String raw : Files.readAllLines(path, StandardCharsets.UTF_8))
        {
            String line = raw.trim();
            if (line.isEmpty())
            {
                continue;
            }
            if (line.startsWith("@")) //$NON-NLS-1$
            {
                addTags(pendingTags, line);
                continue;
            }
            if (isFeature(line))
            {
                featureName = line.substring(line.indexOf(':') + 1).trim();
                featureTags.addAll(pendingTags);
                pendingTags.clear();
                continue;
            }
            if (isScenario(line)) // also covers "Scenario Outline:" / "Сценарий сценарий:"
            {
                int colon = line.indexOf(':'); //$NON-NLS-1$
                String name = colon >= 0 ? line.substring(colon + 1).trim() : ""; //$NON-NLS-1$
                Map<String, Object> scenario = new LinkedHashMap<>();
                scenario.put("name", name); //$NON-NLS-1$
                scenario.put("tags", pendingTags.isEmpty() //$NON-NLS-1$
                    ? (scenarios.isEmpty() ? new ArrayList<>(featureTags) : new ArrayList<Object>())
                    : new ArrayList<>(pendingTags));
                pendingTags.clear();
                currentSteps = new ArrayList<>();
                scenario.put("steps", currentSteps); //$NON-NLS-1$
                scenarios.add(scenario);
                continue;
            }
            if (currentSteps != null && isStep(line))
            {
                currentSteps.add(line);
            }
        }
        out.put("feature", featureName == null ? "" : featureName); //$NON-NLS-1$
        out.put("tags", featureTags);
        out.put("scenarios", scenarios);
        return out;
    }

    private static boolean isFeature(String line)
    {
        // English "Feature:"; Russian "Функционал:" (1C:Enterprise / Vanessa Automation gherkin).
        return line.startsWith("Feature:") || line.startsWith("Функционал:"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static boolean isScenario(String line)
    {
        // English "Scenario" / "Scenario Outline"; Russian "Сценарий:" / "Сценарий сценарий:".
        return line.startsWith("Scenario") || line.startsWith("Сценарий"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static boolean isStep(String line)
    {
        if (line.startsWith("Given ") || line.startsWith("When ") //$NON-NLS-1$ //$NON-NLS-2$
            || line.startsWith("Then ") || line.startsWith("And ") //$NON-NLS-1$ //$NON-NLS-2$
            || line.startsWith("But ") || line.startsWith("* ")) //$NON-NLS-1$ //$NON-NLS-2$
        {
            return true;
        }
        // Russian gherkin (1C:Enterprise / Vanessa Automation).
        return line.startsWith("Дано ") || line.startsWith("Допустим ") //$NON-NLS-1$ //$NON-NLS-2$
            || line.startsWith("Пусть ") || line.startsWith("Когда ") //$NON-NLS-1$ //$NON-NLS-2$
            || line.startsWith("Тогда ") || line.startsWith("То ") //$NON-NLS-1$ //$NON-NLS-2$
            || line.startsWith("И ") || line.startsWith("Но ") //$NON-NLS-1$ //$NON-NLS-2$
            || line.startsWith("А ") || line.startsWith("Если ") //$NON-NLS-1$ //$NON-NLS-2$
            || line.startsWith("К тому же ") || line.startsWith("Также "); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static void addTags(List<String> into, String line)
    {
        for (String t : line.substring(1).split("\\s+")) //$NON-NLS-1$
        {
            if (!t.isEmpty() && !into.contains(t))
            {
                into.add(t);
            }
        }
    }
}
