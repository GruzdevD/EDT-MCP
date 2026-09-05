/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 *
 * edt-mcp-vanessa: JUnit XML report parsing for Vanessa Automation BDD runs.
 */

package com.ditrix.edt.mcp.server.tools.impl.vanessa;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Parses a Vanessa Automation JUnit XML report using the JDK's built-in JAXP/DOM
 * stack. No new OSGi bundles are introduced (the profile is already overloaded with
 * jackson/xml dependencies — see the YAxUnit fix), keeping the bundle lightweight.
 */
public final class JUnitReportParser
{
    private JUnitReportParser()
    {
        // Utility class
    }

    /**
     * Parses {@code junitPath} into a flat, client-friendly JSON structure.
     *
     * @param junitPath absolute path to a junit.xml produced by Vanessa
     * @return map describing the report (suites, test case counts, per-test result)
     * @throws java.io.IOException    if the file is missing/unreadable
     * @throws org.xml.sax.SAXException if the file is not well-formed XML
     */
    public static Map<String, Object> parse(String junitPath) throws Exception
    {
        File file = new File(junitPath);
        if (!file.isFile())
        {
            throw new java.io.FileNotFoundException("JUnit report not found: " + junitPath); //$NON-NLS-1$
        }

        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        // Avoid XXE and keep the parser strict against entity expansion.
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true); //$NON-NLS-1$
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        Document doc = factory.newDocumentBuilder().parse(file);

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("path", junitPath);

        List<Map<String, Object>> suites = new ArrayList<>();
        int totalTests = 0;
        int totalFailures = 0;
        int totalErrors = 0;
        int totalSkipped = 0;

        NodeList suiteNodes = doc.getElementsByTagName("testsuite"); //$NON-NLS-1$
        for (int i = 0; i < suiteNodes.getLength(); i++)
        {
            Element suiteEl = (Element) suiteNodes.item(i);
            Map<String, Object> suite = new LinkedHashMap<>();
            suite.put("name", suiteEl.getAttribute("name")); //$NON-NLS-1$ //$NON-NLS-2$
            suite.put("tests", parseNonNeg(suiteEl.getAttribute("tests"))); //$NON-NLS-1$ //$NON-NLS-2$
            suite.put("failures", parseNonNeg(suiteEl.getAttribute("failures"))); //$NON-NLS-1$ //$NON-NLS-2$
            suite.put("errors", parseNonNeg(suiteEl.getAttribute("errors"))); //$NON-NLS-1$ //$NON-NLS-2$
            suite.put("skipped", parseNonNeg(suiteEl.getAttribute("skipped"))); //$NON-NLS-1$ //$NON-NLS-2$
            suite.put("time", safeTime(suiteEl.getAttribute("time"))); //$NON-NLS-1$ //$NON-NLS-2$

            totalTests += ((Integer) suite.get("tests")).intValue(); //$NON-NLS-1$
            totalFailures += ((Integer) suite.get("failures")).intValue(); //$NON-NLS-1$
            totalErrors += ((Integer) suite.get("errors")).intValue(); //$NON-NLS-1$
            totalSkipped += ((Integer) suite.get("skipped")).intValue(); //$NON-NLS-1$

            List<Map<String, Object>> cases = new ArrayList<>();
            NodeList children = suiteEl.getChildNodes();
            for (int k = 0; k < children.getLength(); k++)
            {
                Node child = children.item(k);
                if (child.getNodeType() != Node.ELEMENT_NODE
                    || !"testcase".equals(child.getNodeName())) //$NON-NLS-1$
                {
                    continue;
                }
                Element tc = (Element) child;
                Map<String, Object> c = new LinkedHashMap<>();
                c.put("name", tc.getAttribute("name")); //$NON-NLS-1$ //$NON-NLS-2$
                c.put("classname", tc.getAttribute("classname")); //$NON-NLS-1$ //$NON-NLS-2$
                c.put("time", safeTime(tc.getAttribute("time"))); //$NON-NLS-1$ //$NON-NLS-2$

                String status = "passed"; //$NON-NLS-1$
                String detail = null;
                Element failure = firstChildElement(tc, "failure"); //$NON-NLS-1$
                Element error = firstChildElement(tc, "error"); //$NON-NLS-1$
                Element skipped = firstChildElement(tc, "skipped"); //$NON-NLS-1$
                if (failure != null)
                {
                    status = "failed"; //$NON-NLS-1$
                    detail = failure.getAttribute("message"); //$NON-NLS-1$
                }
                else if (error != null)
                {
                    status = "error"; //$NON-NLS-1$
                    detail = error.getAttribute("message"); //$NON-NLS-1$
                }
                else if (skipped != null)
                {
                    status = "skipped"; //$NON-NLS-1$
                }
                c.put("status", status);
                if (detail != null && !detail.isEmpty())
                {
                    c.put("message", detail);
                }
                cases.add(c);
            }
            suite.put("testcases", cases);
            suites.add(suite);
        }

        report.put("suites", suites);
        report.put("testCount", Integer.valueOf(totalTests));
        report.put("failureCount", Integer.valueOf(totalFailures));
        report.put("errorCount", Integer.valueOf(totalErrors));
        report.put("skippedCount", Integer.valueOf(totalSkipped));
        report.put("passedCount", Integer.valueOf(totalTests - totalFailures - totalErrors - totalSkipped));
        // Overall BDD verdict for convenience.
        report.put("verdict", totalErrors > 0 || totalFailures > 0 ? "failed" : "passed"); //$NON-NLS-1$ //$NON-NLS-2$
        return report;
    }

    private static int parseNonNeg(String value)
    {
        if (value == null || value.isEmpty())
        {
            return 0;
        }
        try
        {
            return Math.max(0, Integer.parseInt(value.trim()));
        }
        catch (NumberFormatException e)
        {
            return 0;
        }
    }

    private static double safeTime(String value)
    {
        if (value == null || value.isEmpty())
        {
            return 0.0d;
        }
        try
        {
            return Double.parseDouble(value.trim());
        }
        catch (NumberFormatException e)
        {
            return 0.0d;
        }
    }

    private static Element firstChildElement(Element parent, String name)
    {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++)
        {
            Node child = children.item(i);
            if (child.getNodeType() == Node.ELEMENT_NODE && name.equals(child.getNodeName()))
            {
                return (Element) child;
            }
        }
        return null;
    }
}
