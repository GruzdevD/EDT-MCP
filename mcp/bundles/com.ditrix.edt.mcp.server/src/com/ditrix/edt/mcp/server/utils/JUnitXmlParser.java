/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * Parses a JUnit XML report file into a {@link JUnitTestResults} model.
 *
 * Supports the standard {@code testsuite}/{@code testcase} layout, including
 * the {@code testsuites} root wrapper. The parser is hardened against XXE,
 * SSRF and entity-expansion attacks.
 */
public final class JUnitXmlParser
{
    private static final String MESSAGE = "message"; //$NON-NLS-1$
    private static final String[] COUNT_ATTRIBUTES = {"tests", "failures", "errors", "skipped"}; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
    private static final String[] OUTCOMES = {"failure", "error", "skipped"}; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

    private JUnitXmlParser()
    {
        // utility class
    }

    /**
     * Parses a JUnit XML file. Throws on parse failure.
     */
    public static JUnitTestResults parse(File junitXml) throws Exception
    {
        DocumentBuilder builder = newSecureBuilder();
        Document doc = builder.parse(junitXml);
        return parseDocument(doc);
    }

    /**
     * Parses JUnit XML from an input stream. Useful for unit tests.
     */
    public static JUnitTestResults parse(InputStream in) throws Exception
    {
        DocumentBuilder builder = newSecureBuilder();
        Document doc = builder.parse(in);
        return parseDocument(doc);
    }

    /** Requires actual testcase evidence from a complete, flat YAxUnit JUnit report. */
    public static JUnitTestResults parseYaxunitReport(File junitXml) throws Exception
    {
        Document document = newSecureBuilder().parse(junitXml);
        Element root = document.getDocumentElement();
        boolean wrapped = "testsuites".equals(root.getTagName()); //$NON-NLS-1$
        if (!wrapped && !"testsuite".equals(root.getTagName())) //$NON-NLS-1$
        {
            throw invalidReport();
        }
        NodeList suites = document.getElementsByTagName("testsuite"); //$NON-NLS-1$
        if (suites.getLength() == 0)
        {
            throw invalidReport();
        }
        for (String name : OUTCOMES)
        {
            NodeList nodes = document.getElementsByTagName(name);
            for (int i = 0; i < nodes.getLength(); i++)
            {
                if (!(nodes.item(i).getParentNode() instanceof Element test) || !"testcase".equals(test.getTagName())) //$NON-NLS-1$
                {
                    throw invalidReport();
                }
            }
        }
        NodeList cases = document.getElementsByTagName("testcase"); //$NON-NLS-1$
        for (int i = 0; i < cases.getLength(); i++)
        {
            Element test = (Element)cases.item(i);
            if (!(test.getParentNode() instanceof Element suite) || !"testsuite".equals(suite.getTagName()) //$NON-NLS-1$
                || test.getAttribute("name").isBlank()) //$NON-NLS-1$
            {
                throw invalidReport();
            }
            int outcomes = 0;
            for (String name : OUTCOMES)
            {
                outcomes += test.getElementsByTagName(name).getLength();
            }
            if (outcomes > 1)
            {
                throw invalidReport();
            }
        }
        JUnitTestResults results = new JUnitTestResults();
        for (int i = 0; i < suites.getLength(); i++)
        {
            Element suite = (Element)suites.item(i);
            if (wrapped ? suite.getParentNode() != root : suite != root)
            {
                throw invalidReport();
            }
            int[] counts = countSuiteCases(suite, results);
            requireCounts(suite, counts);
            results.addToTotals(counts[0], counts[1], counts[2], counts[3]);
        }
        if (wrapped)
        {
            requireCounts(root, new int[] {results.getTotal(), results.getFailures(), results.getErrors(),
                results.getSkipped()});
        }
        if (results.getTotal() <= results.getSkipped())
        {
            throw invalidReport();
        }
        return results;
    }

    private static void requireCounts(Element element, int[] counts) throws IOException
    {
        for (int i = 0; i < COUNT_ATTRIBUTES.length; i++)
        {
            String name = COUNT_ATTRIBUTES[i];
            if (element.hasAttribute(name) && getIntAttr(element, name, -1) != counts[i])
            {
                throw invalidReport();
            }
        }
    }

    private static IOException invalidReport()
    {
        return new IOException("The YAxUnit report has no executed tests or has an invalid structure or counts."); //$NON-NLS-1$
    }

    private static JUnitTestResults parseDocument(Document doc)
    {
        doc.getDocumentElement().normalize();

        JUnitTestResults results = new JUnitTestResults();

        NodeList suiteNodes = doc.getElementsByTagName("testsuite"); //$NON-NLS-1$
        for (int i = 0; i < suiteNodes.getLength(); i++)
        {
            Element suite = (Element) suiteNodes.item(i);

            int attrTests = getIntAttr(suite, "tests", 0); //$NON-NLS-1$
            int attrFailures = getIntAttr(suite, "failures", 0); //$NON-NLS-1$
            int attrErrors = getIntAttr(suite, "errors", 0); //$NON-NLS-1$
            int attrSkipped = getIntAttr(suite, "skipped", 0); //$NON-NLS-1$

            // Scan the suite's testcases: record each failure/error/skipped into 'results'
            // and return the fallback node counts {caseCount, failures, errors, skipped} // NOSONAR explanatory comment, not commented-out code
            // for producers that omit or misreport the testsuite attributes.
            int[] nodeCounts = countSuiteCases(suite, results);

            // Use the higher of attribute count vs node count to handle producers
            // that omit or misreport testsuite attributes.
            int finalTests = Math.max(attrTests, nodeCounts[0]);
            int finalFailures = Math.max(attrFailures, nodeCounts[1]);
            int finalErrors = Math.max(attrErrors, nodeCounts[2]);
            int finalSkipped = Math.max(attrSkipped, nodeCounts[3]);
            results.addToTotals(finalTests, finalFailures, finalErrors, finalSkipped);
        }

        // Standalone testcases without a wrapping testsuite — count them only.
        if (suiteNodes.getLength() == 0)
        {
            NodeList caseNodes = doc.getElementsByTagName("testcase"); //$NON-NLS-1$
            results.setTotal(caseNodes.getLength());
        }

        return results;
    }

    /**
     * Scans every {@code <testcase>} of a {@code <testsuite>}: records each failure / error / skipped
     * child into {@code results} and returns the per-suite fallback counts
     * {@code {caseCount, failures, errors, skipped}} — the inner testcase loop of {@link #parseDocument},
     * extracted verbatim. The counts let the caller reconcile against the suite-level attributes.
     */
    private static int[] countSuiteCases(Element suite, JUnitTestResults results)
    {
        int nodeFailures = 0;
        int nodeErrors = 0;
        int nodeSkipped = 0;

        NodeList caseNodes = suite.getElementsByTagName("testcase"); //$NON-NLS-1$
        for (int j = 0; j < caseNodes.getLength(); j++)
        {
            Element testCase = (Element) caseNodes.item(j);
            String className = testCase.getAttribute("classname"); //$NON-NLS-1$
            String testName = testCase.getAttribute("name"); //$NON-NLS-1$
            String fullName = (className != null && !className.isEmpty())
                    ? className + "." + testName //$NON-NLS-1$
                    : testName;

            NodeList failureNodes = testCase.getElementsByTagName("failure"); //$NON-NLS-1$
            if (failureNodes.getLength() > 0)
            {
                nodeFailures++;
                Element failure = (Element) failureNodes.item(0);
                results.addFailure(new JUnitTestResults.TestCase(fullName,
                        failure.getAttribute(MESSAGE),
                        failure.getTextContent()));
            }

            NodeList errorNodes = testCase.getElementsByTagName("error"); //$NON-NLS-1$
            if (errorNodes.getLength() > 0)
            {
                nodeErrors++;
                Element error = (Element) errorNodes.item(0);
                results.addError(new JUnitTestResults.TestCase(fullName,
                        error.getAttribute(MESSAGE),
                        error.getTextContent()));
            }

            NodeList skippedNodes = testCase.getElementsByTagName("skipped"); //$NON-NLS-1$
            if (skippedNodes.getLength() > 0)
            {
                nodeSkipped++;
                Element skip = (Element) skippedNodes.item(0);
                results.addSkipped(new JUnitTestResults.TestCase(fullName,
                        skip.getAttribute(MESSAGE),
                        null));
            }
        }
        return new int[] {caseNodes.getLength(), nodeFailures, nodeErrors, nodeSkipped};
    }

    private static DocumentBuilder newSecureBuilder() throws Exception // NOSONAR propagates checked exceptions across the reflective boundary by design
    {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        // Harden XML parsing against XXE/SSRF and entity-expansion attacks.
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true); //$NON-NLS-1$
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false); //$NON-NLS-1$
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false); //$NON-NLS-1$
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false); //$NON-NLS-1$
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, ""); //$NON-NLS-1$
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, ""); //$NON-NLS-1$
        return factory.newDocumentBuilder();
    }

    private static int getIntAttr(Element element, String attr, int defaultValue)
    {
        String value = element.getAttribute(attr);
        if (value == null || value.isEmpty())
        {
            return defaultValue;
        }
        try
        {
            return Integer.parseInt(value);
        }
        catch (NumberFormatException e)
        {
            return defaultValue;
        }
    }
}
