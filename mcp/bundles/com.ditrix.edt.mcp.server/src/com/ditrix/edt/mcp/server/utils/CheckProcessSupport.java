/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.lang.reflect.Method;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.Platform;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceReference;

import com.ditrix.edt.mcp.server.Activator;

/**
 * Shared backend for toggling a project's <em>massive check process</em> — the EDT
 * setting a user would normally flip in the project property page «Настройки процесса
 * проверок» («Отключить режим массовых проверок», {@code disableMassiveChecks}).
 *
 * <p>EDT runs an expensive check pass over derived data when an infobase is
 * synchronized/updated ({@code "SYNCHRONIZATION_MANAGER"} service events, among
 * others); that is exactly the "расширенные проверки и валидации" the caller wants to
 * hold off <em>before</em> an {@code update_database} and restore <em>after</em>. The
 * project-scoped preference is {@code disableMassiveChecks} under the
 * {@code com.e1c.g5.v8.dt.check} node, but it is <b>not</b> written directly: the EDT
 * property page goes through the OSGi service
 * {@code com.e1c.g5.v8.dt.check.settings.ICheckRepository} whose
 * {@code setMassiveCheckProcessDisabled(boolean, project)} flushes the preference AND
 * live (re)activates the check contexts without a restart. This helper mirrors that
 * path so the toggle behaves exactly like the EDT UI.
 *
 * <p>The {@link ICheckRepository} service is acquired BY CLASS NAME through the owning
 * bundle's context (the same reflective pattern as
 * {@code StandaloneServerSupport.acquireService} / {@code InfobaseAccessSupport}), so
 * this bundle needs no Require-Bundle/Import-Package on the check feature.
 */
public final class CheckProcessSupport
{
    /** Symbolic name of the bundle that registers the {@link ICheckRepository} service. */
    private static final String CHECK_BUNDLE_ID = "com.e1c.g5.v8.dt.check"; //$NON-NLS-1$

    /** FQN of the service interface (exported by the check bundle; resolved reflectively). */
    private static final String REPOSITORY_CLASS =
        "com.e1c.g5.v8.dt.check.settings.ICheckRepository"; //$NON-NLS-1$

    /** Reflection method names on the repository service. */
    private static final String M_READ = "isMassiveCheckProcessDisabled"; //$NON-NLS-1$
    private static final String M_WRITE = "setMassiveCheckProcessDisabled"; //$NON-NLS-1$

    private CheckProcessSupport()
    {
    }

    /**
     * Reads whether the project's massive check process is currently disabled.
     *
     * @param project the target project (must not be {@code null})
     * @return the current state, or {@code null} when the check service is unavailable
     */
    public static Boolean isMassiveCheckProcessDisabled(IProject project)
    {
        Object repo = acquireRepository();
        if (repo == null || project == null)
        {
            return null;
        }
        try
        {
            Method read = repo.getClass().getMethod(M_READ, IProject.class);
            return (Boolean)read.invoke(repo, project);
        }
        catch (Exception e) // NOSONAR probe must never crash the tool
        {
            Activator.logError("check process: could not read disableMassiveChecks for " //$NON-NLS-1$
                + project.getName(), e);
            return null;
        }
    }

    /**
     * Sets (or, with {@code false}, restores) the project's massive check process flag
     * through the {@code ICheckRepository} service — the same path the EDT property page
     * uses, so the change both persists to the project preference and takes effect
     * immediately (no restart).
     *
     * @param disabled {@code true} to disable the massive check process, {@code false} to
     *            re-enable it
     * @param project the target project (must not be {@code null})
     * @return {@code null} on success, otherwise an actionable error message (service
     *         unavailable, or the platform call failed)
     */
    public static String setMassiveCheckProcessDisabled(boolean disabled, IProject project)
    {
        if (project == null)
        {
            return "No target project to set the massive-check preference for."; //$NON-NLS-1$
        }
        Object repo = acquireRepository();
        if (repo == null)
        {
            return "EDT check-repository service is not available (the check plugin may not be " //$NON-NLS-1$
                + "installed or ready)."; //$NON-NLS-1$
        }
        try
        {
            Method write = repo.getClass().getMethod(M_WRITE, boolean.class, IProject.class);
            write.invoke(repo, disabled, project);
            return null;
        }
        catch (Exception e) // NOSONAR reflective setter — surface as an actionable error
        {
            Activator.logError("check process: setMassiveCheckProcessDisabled failed for " //$NON-NLS-1$
                + project.getName(), e);
            return "Failed to update the massive-check preference for project '" //$NON-NLS-1$
                + project.getName() + "': " + e.getMessage(); //$NON-NLS-1$
        }
    }

    /**
     * Resolves the {@code ICheckRepository} service from the OSGi registry BY CLASS NAME
     * (reflectively), so this bundle has no compile/bundle dependency on the check
     * feature. Returns {@code null} when the bundle or service is unavailable.
     */
    private static Object acquireRepository()
    {
        try
        {
            Bundle bundle = Platform.getBundle(CHECK_BUNDLE_ID);
            if (bundle == null)
            {
                Activator.logError("check process: bundle '" + CHECK_BUNDLE_ID //$NON-NLS-1$
                    + "' not found — the EDT check plugin is not installed", null); //$NON-NLS-1$
                return null;
            }
            BundleContext context = bundle.getBundleContext();
            if (context == null)
            {
                bundle.start(Bundle.START_TRANSIENT);
                context = bundle.getBundleContext();
            }
            if (context == null)
            {
                return null;
            }
            ServiceReference<?> ref = context.getServiceReference(REPOSITORY_CLASS);
            return ref != null ? context.getService(ref) : null;
        }
        catch (Throwable t) // NOSONAR probe must never crash the tool
        {
            Activator.logError("check process: could not acquire the check-repository service", t); //$NON-NLS-1$
            return null;
        }
    }
}
