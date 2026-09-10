/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ozon.edt.mcp.server.tools.impl.vanessa;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests for the pure (EDT-free) {@link VanessaBootstrap} core: layout provisioning,
 * template token filling and the doctor readiness report. The heavy runtime download
 * (network) and the workspace-touching {@link Job} wrappers are not exercised here.
 */
public class VanessaBootstrapTest
{
    private String oldHome;
    private Path home;
    private Path projectRoot;

    private static final String ENV_TEMPLATE =
        "PROJ=\"@PROJECT@\"\n" //$NON-NLS-1$
            + "VAPARAMS=\"@VAPARAMS@\"\n" //$NON-NLS-1$
            + "FEATURES_DIR=\"@FEATURES_DIR@\"\n" //$NON-NLS-1$
            + "EPF=\"@EPF@\"\n"; //$NON-NLS-1$

    private static final String VAPARAMS_TEMPLATE =
        "{ \"ОтчетJUnit\": { \"КаталогВыгрузкиJUnit\": \"@FEATURES_DIR@\" } }"; //$NON-NLS-1$

    @Before
    public void setUp() throws Exception
    {
        oldHome = System.getProperty("user.home"); //$NON-NLS-1$
        home = Files.createTempDirectory("vanessa-bootstrap-test-"); //$NON-NLS-1$
        System.setProperty("user.home", home.toString()); //$NON-NLS-1$
        projectRoot = Files.createTempDirectory("vanessa-projroot-"); //$NON-NLS-1$
        Files.createDirectories(projectRoot);
    }

    @After
    public void tearDown() throws Exception
    {
        System.setProperty("user.home", oldHome); //$NON-NLS-1$
        deleteRecursively(projectRoot);
        deleteRecursively(home);
    }

    private static void deleteRecursively(Path dir) throws Exception
    {
        if (dir != null && Files.exists(dir))
        {
            try (java.util.stream.Stream<Path> s = Files.walk(dir))
            {
                s.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
    }

    @Test
    public void testProvisionLayoutCreatesTreeAndIsIdempotent() throws Exception
    {
        Path vanessa = VanessaBootstrap.provisionLayout(
            projectRoot, "afm", ENV_TEMPLATE, VAPARAMS_TEMPLATE); //$NON-NLS-1$
        assertNotNull(vanessa);
        assertTrue(Files.isDirectory(vanessa.resolve("features"))); //$NON-NLS-1$
        assertTrue(Files.isDirectory(vanessa.resolve("out"))); //$NON-NLS-1$

        Path env = vanessa.resolve("env.sh"); //$NON-NLS-1$
        Path vaparams = vanessa.resolve("VAParams.json"); //$NON-NLS-1$
        assertTrue(Files.isRegularFile(env));
        assertTrue(Files.isRegularFile(vaparams));

        // env.sh is token-filled, not left as the raw template
        String envText = Files.readString(env, StandardCharsets.UTF_8);
        assertFalse(envText.contains("@PROJECT@")); //$NON-NLS-1$
        assertTrue(envText.contains("PROJ=\"afm\"")); //$NON-NLS-1$
        assertTrue(envText.contains(VanessaBootstrap.epfCacheFile().toString()));

        // Idempotent: an operator edit to env.sh is preserved on a second provision.
        String edited = envText + "# operator note\n"; //$NON-NLS-1$
        Files.writeString(env, edited, StandardCharsets.UTF_8);
        VanessaBootstrap.provisionLayout(projectRoot, "afm", ENV_TEMPLATE, VAPARAMS_TEMPLATE); //$NON-NLS-1$
        assertEquals(edited, Files.readString(env, StandardCharsets.UTF_8));
    }

    @Test
    public void testFillTemplateSubstitutesTokensOnlyKnownKeys()
    {
        Map<String, String> vars = new LinkedHashMap<>();
        vars.put("A", "one"); //$NON-NLS-1$ //$NON-NLS-2$
        vars.put("B", "two"); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("one-two-@C@",
            VanessaBootstrap.fillTemplate("@A@-@B@-@C@", vars)); //$NON-NLS-1$
    }

    @Test
    public void testDoctorReportsMissingThenReady() throws Exception
    {
        // No .vanessa yet -> everything missing, not ready.
        VanessaBootstrap.Doctor before =
            VanessaBootstrap.doctor(projectRoot, "afm"); //$NON-NLS-1$
        assertFalse(before.layout);
        assertFalse(before.envSh);
        assertFalse(before.vaparams);
        assertFalse(before.epf);
        assertFalse(before.ready());

        // Provision the layout but keep the shared epf cache empty -> still not ready.
        VanessaBootstrap.provisionLayout(
            projectRoot, "afm", ENV_TEMPLATE, VAPARAMS_TEMPLATE); //$NON-NLS-1$
        VanessaBootstrap.Doctor afterLayout =
            VanessaBootstrap.doctor(projectRoot, "afm"); //$NON-NLS-1$
        assertTrue(afterLayout.layout);
        assertTrue(afterLayout.envSh);
        assertTrue(afterLayout.vaparams);
        assertFalse(afterLayout.epf);
        assertFalse(afterLayout.ready());

        // Place the epf runtime in the shared cache -> ready.
        Files.createDirectories(VanessaBootstrap.epfCacheFile().getParent());
        Files.writeString(VanessaBootstrap.epfCacheFile(), "epf", StandardCharsets.UTF_8); //$NON-NLS-1$
        VanessaBootstrap.Doctor ready =
            VanessaBootstrap.doctor(projectRoot, "afm"); //$NON-NLS-1$
        assertTrue(ready.epf);
        assertTrue(ready.ready());
    }

    @Test
    public void testUpdateEpfInEnvRewritesAndInserts() throws Exception
    {
        Path env = projectRoot.resolve("env.sh"); //$NON-NLS-1$
        Files.writeString(env, "PROJ=\"afm\"\nEPF=\"/old/path.epf\"\n", StandardCharsets.UTF_8); //$NON-NLS-1$
        Path epf = VanessaBootstrap.epfCacheFile();

        VanessaBootstrap.updateEpfInEnv(env, epf);
        String afterReplace = Files.readString(env, StandardCharsets.UTF_8);
        assertTrue(afterReplace.contains("EPF=\"" + epf + "\"")); //$NON-NLS-1$
        assertFalse(afterReplace.contains("/old/path.epf")); //$NON-NLS-1$

        // Missing EPF line -> appended, not duplicated.
        Path env2 = projectRoot.resolve("env2.sh"); //$NON-NLS-1$
        Files.writeString(env2, "PROJ=\"fom\"\n", StandardCharsets.UTF_8); //$NON-NLS-1$
        VanessaBootstrap.updateEpfInEnv(env2, epf);
        String appended = Files.readString(env2, StandardCharsets.UTF_8);
        assertTrue(appended.contains("EPF=\"" + epf + "\"")); //$NON-NLS-1$
        assertEquals(1, appended.lines().filter(l -> l.trim().startsWith("EPF=")).count()); //$NON-NLS-1$
    }
}
