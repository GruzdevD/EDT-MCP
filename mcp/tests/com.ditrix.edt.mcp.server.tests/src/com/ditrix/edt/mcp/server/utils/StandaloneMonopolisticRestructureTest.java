/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

import org.junit.Test;

import com.e1c.g5.dt.applications.IApplication;
import com.e1c.g5.dt.applications.IApplicationArtifact;
import com.e1c.g5.dt.applications.IApplicationType;

/**
 * Tests for {@link StandaloneMonopolisticRestructure}.
 *
 * <p>The part that runs headlessly is the global arm/disarm/request/consume signal registry (the
 * abort-signal handshake between the tool thread and the answerer's callback thread) and the
 * deterministic early-fail path of {@link #escalate} when the target application is not a
 * file-backed standalone server (a plain {@code IApplication} with no {@code getModule()} — so
 * {@code moduleOfApplication} yields {@code null} — must produce the "not backed by a file infobase"
 * refusal without touching any server). The full export/stop/apply path needs a live standalone
 * server and a real {@code 1cv8}; that is exercised live (Tier 2), not here.
 */
public class StandaloneMonopolisticRestructureTest
{
    /** A bare {@code IApplication} with no server module: the non-file-backed early-fail path. */
    private static IApplication plainApplication()
    {
        return new IApplication()
        {
            @Override
            public String getId()
            {
                return "plain-app"; //$NON-NLS-1$
            }

            @Override
            public String getName()
            {
                return "Plain App"; //$NON-NLS-1$
            }

            @Override
            public org.eclipse.core.resources.IProject getProject()
            {
                return null;
            }

            @Override
            public IApplicationType getType()
            {
                return null;
            }

            @Override
            public List<IApplicationArtifact> getArtifacts()
            {
                return Collections.emptyList();
            }

            @Override
            public Optional<String> getRequiredVersion()
            {
                return Optional.empty();
            }

            @Override
            public IApplication getApplication()
            {
                return this;
            }

            @Override
            @SuppressWarnings("rawtypes") // NOSONAR matching Eclipse's raw IAdaptable.getAdapter(Class)
            public Object getAdapter(Class adapter)
            {
                return null;
            }
        };
    }

    @Test
    public void armMakesArmedUntilDisarmed()
    {
        try
        {
            assertFalse(StandaloneMonopolisticRestructure.isArmedExternal());
            StandaloneMonopolisticRestructure.armExternal();
            assertTrue(StandaloneMonopolisticRestructure.isArmedExternal());
        }
        finally
        {
            StandaloneMonopolisticRestructure.disarmExternal();
        }
        assertFalse(StandaloneMonopolisticRestructure.isArmedExternal());
    }

    @Test
    public void consumeIsOneShot()
    {
        // Clean any stale signal from another test.
        StandaloneMonopolisticRestructure.consumeRestructureRequested();
        try
        {
            assertFalse(StandaloneMonopolisticRestructure.consumeRestructureRequested());
            StandaloneMonopolisticRestructure.requestRestructure("exclusive lock question"); //$NON-NLS-1$
            assertTrue(StandaloneMonopolisticRestructure.consumeRestructureRequested());
            assertFalse(StandaloneMonopolisticRestructure.consumeRestructureRequested());
        }
        finally
        {
            StandaloneMonopolisticRestructure.consumeRestructureRequested();
        }
    }

    @Test
    public void escalateOnNonFileBackedApplicationRefusesWithoutStopping()
    {
        StandaloneMonopolisticRestructure.Outcome o =
            StandaloneMonopolisticRestructure.escalate(plainApplication(), "plain-app", //$NON-NLS-1$
                java.nio.file.Path.of("ignored"), "ignored"); //$NON-NLS-1$ //$NON-NLS-2$
        assertNotNull(o);
        assertFalse(o.applied);
        assertFalse("no server was touched, so none is left stopped", o.serverStopped); //$NON-NLS-1$
        assertTrue("refusal must say the target is not file-backed", //$NON-NLS-1$
            o.message.contains("file infobase")); //$NON-NLS-1$
    }
}
