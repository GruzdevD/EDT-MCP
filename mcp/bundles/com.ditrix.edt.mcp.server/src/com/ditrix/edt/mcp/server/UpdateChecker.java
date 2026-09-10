/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import com.ditrix.edt.mcp.server.preferences.PreferenceConstants;
import com.ditrix.edt.mcp.server.protocol.McpConstants;
import com.ditrix.edt.mcp.server.tools.impl.selfupdate.PluginSelfUpdate;

/**
 * Checks whether a newer build of the EDT MCP Server plugin is published on its
 * own git repo (the {@code update-site} branch that {@link PluginSelfUpdate}
 * maintains), rather than on the upstream DitriX GitHub. Comparing against the
 * upstream's tags would be meaningless for this fork, whose version scheme is
 * decoupled from the original — so the check sources from the same
 * {@code update-site} branch the {@code plugin_check_for_update} /
 * {@code plugin_update} tools deploy.
 *
 * <p>The check interval is controlled by the preference
 * {@link PreferenceConstants#PREF_UPDATE_CHECK_INTERVAL}:
 * <ul>
 *   <li>{@code on_startup} – once per EDT session, 60 s after startup</li>
 *   <li>{@code hourly}     – first check after 60 s, then every hour</li>
 *   <li>{@code daily}      – first check after 60 s, then every 24 h</li>
 *   <li>{@code never}      – disabled</li>
 * </ul>
 */
public final class UpdateChecker // NOSONAR intentional singleton (Eclipse service / getInstance); a single instance is by design
{
    /** The personal repo the built plugin artifacts are published to. */
    private static final String REPO_URL = PluginSelfUpdate.DEFAULT_REPO;

    /** Branch holding the built plugin jars (same as the self-update tools). */
    private static final String UPDATE_BRANCH = PluginSelfUpdate.DEFAULT_BRANCH;

    /** Repo page URL opened in a browser on "Download" (set to the public fork). */
    public static final String RELEASES_PAGE_URL =
        "https://github.com/<your-github-account>/EDT-MCP"; //$NON-NLS-1$

    /** Initial delay before the very first check (ms). */
    private static final long INITIAL_DELAY_MS = 10_000L;

    private static final UpdateChecker INSTANCE = new UpdateChecker();

    private final AtomicBoolean updateAvailable  = new AtomicBoolean(false);
    private final AtomicReference<String> latestVersion = new AtomicReference<>("");
    private final AtomicReference<String> releaseNotes  = new AtomicReference<>("");
    private final AtomicReference<String> releaseUrl    = new AtomicReference<>(RELEASES_PAGE_URL);

    private ScheduledExecutorService scheduler;

    private UpdateChecker()
    {
        // private constructor – use getInstance()
    }

    /** Returns the singleton instance. */
    public static UpdateChecker getInstance()
    {
        return INSTANCE;
    }

    /**
     * Reads the current preference and schedules the update check accordingly.
     * Safe to call on every EDT startup – cancels any previous scheduler first.
     */
    public void scheduleCheck()
    {
        String interval = readIntervalPref();

        if (PreferenceConstants.UPDATE_CHECK_NEVER.equals(interval))
        {
            Activator.logInfo("EDT MCP Server update check disabled (preference: never)"); //$NON-NLS-1$
            return;
        }

        // Cancel previous scheduler if any (e.g. server restart)
        stopScheduler();

        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "MCP-Update-Checker"); //$NON-NLS-1$
            t.setDaemon(true);
            return t;
        });

        if (PreferenceConstants.UPDATE_CHECK_ON_STARTUP.equals(interval))
        {
            // Single shot – only once per session
            scheduler.schedule(this::performCheck, INITIAL_DELAY_MS, TimeUnit.MILLISECONDS);
        }
        else if (PreferenceConstants.UPDATE_CHECK_HOURLY.equals(interval))
        {
            scheduler.scheduleAtFixedRate(
                this::performCheck,
                INITIAL_DELAY_MS, TimeUnit.HOURS.toMillis(1),
                TimeUnit.MILLISECONDS);
        }
        else if (PreferenceConstants.UPDATE_CHECK_DAILY.equals(interval))
        {
            scheduler.scheduleAtFixedRate(
                this::performCheck,
                INITIAL_DELAY_MS, TimeUnit.DAYS.toMillis(1),
                TimeUnit.MILLISECONDS);
        }
    }

    /**
     * Runs the update check synchronously on the calling thread (no delay).
     * Can be called from a background thread (e.g. from the UI "Check now" button handler).
     */
    public void checkNow()
    {
        performCheck();
    }

    /** Cancels any running scheduled checks. */
    public void stopScheduler()
    {
        if (scheduler != null && !scheduler.isShutdown())
        {
            scheduler.shutdownNow();
            scheduler = null;
        }
    }

    /** @return {@code true} if a newer build was found on the plugin's git repo. */
    public boolean isUpdateAvailable()
    {
        return updateAvailable.get();
    }

    /**
     * @return the latest version string (e.g. {@code "1.25.0"}), or an empty
     *         string if the check has not completed yet or failed.
     */
    public String getLatestVersion()
    {
        return latestVersion.get();
    }

    /**
     * @return the release notes (markdown text) of the latest release, or an empty string.
     */
    public String getReleaseNotes()
    {
        return releaseNotes.get();
    }

    /**
     * @return the URL of the plugin's repo page (opened on "Download").
     */
    public String getReleaseUrl()
    {
        return releaseUrl.get();
    }

    // -----------------------------------------------------------------------
    // Internal helpers
    // -----------------------------------------------------------------------

    private void performCheck()
    {
        Activator.logInfo("EDT MCP Server update check started (current: " + McpConstants.PLUGIN_VERSION + ")"); //$NON-NLS-1$ //$NON-NLS-2$
        try
        {
            String installed = PluginSelfUpdate.installedBundleVersion();

            // A fork compare against the upstream DitriX tags would always report
            // their 2.x as newer than our 1.x — pointless. Instead source from our
            // own published builds, so the indicator only fires on a real new build.
            Path clone = PluginSelfUpdate.ensureClone(
                System.getProperty("user.home"), REPO_URL, UPDATE_BRANCH); //$NON-NLS-1$
            String jar = PluginSelfUpdate.highestBundleJar(clone.resolve("plugins")); //$NON-NLS-1$
            if (jar == null)
            {
                updateAvailable.set(false);
                Activator.logInfo("EDT MCP Server update check: no published build in branch '" //$NON-NLS-1$
                    + UPDATE_BRANCH + "'"); //$NON-NLS-1$
                return;
            }

            String available = PluginSelfUpdate.versionOfJar(jar);
            latestVersion.set(available);
            releaseUrl.set(RELEASES_PAGE_URL);

            boolean newer = PluginSelfUpdate.isNewer(available, installed);
            Activator.logInfo("EDT MCP Server update check: available=" + available //$NON-NLS-1$
                + " installed=" + installed //$NON-NLS-1$
                + " isNewer=" + newer); //$NON-NLS-1$

            if (newer)
            {
                if (!updateAvailable.getAndSet(true))
                {
                    Activator.logInfo("New EDT MCP Server version available: " + available //$NON-NLS-1$
                        + " (current: " + McpConstants.PLUGIN_VERSION + ")"); //$NON-NLS-1$ //$NON-NLS-2$
                }
            }
            else
            {
                // Reset flag in case the user installed an update mid-session
                updateAvailable.set(false);
                Activator.logInfo("EDT MCP Server is up to date: " + McpConstants.PLUGIN_VERSION); //$NON-NLS-1$
            }
        }
        catch (Exception e)
        {
            // Network errors are not critical – just log and continue
            updateAvailable.set(false);
            Activator.logInfo("EDT MCP Server update check failed: " + e.getClass().getSimpleName() //$NON-NLS-1$
                + ": " + e.getMessage()); //$NON-NLS-1$
        }
    }

    /**
     * Reads the update-check-interval preference.
     * Falls back to {@code on_startup} when the plugin/store is unavailable.
     */
    private static String readIntervalPref()
    {
        try
        {
            if (Activator.getDefault() != null)
            {
                String val = Activator.getDefault().getPreferenceStore()
                    .getString(PreferenceConstants.PREF_UPDATE_CHECK_INTERVAL);
                if (val != null && !val.isEmpty())
                {
                    return val;
                }
            }
        }
        catch (Exception ignored)
        {
            // ignore
        }
        return PreferenceConstants.DEFAULT_UPDATE_CHECK_INTERVAL;
    }
}
