/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IPath;

/**
 * One-line façade for the standalone structural-update (exclusive-lock) matcher, so the three call
 * sites that act on a standalone-hosted file base ({@code update_database} and the two YAXUnit
 * launch paths) share the same workspace-root resolution and restructure lambda instead of duplicating
 * it and drifting apart.
 *
 * <p>Pair {@link #arm(String)} with {@link #disarm(String)} on the same infobase around the update /
 * launch. The confirmer then, when EDT raises its exclusive-infobase-lock modal, applies the pending
 * restructure through the server's admin-SSH gate inside the running process and presses retry; a
 * failure cancels the dialog and records an actionable error for the caller.
 */
public final class StandaloneExclusiveRestructure
{
    private StandaloneExclusiveRestructure()
    {
        // Utility
    }

    /**
     * Arms the exclusive-lock restructure matcher for a structural update of {@code infobaseName}.
     * No-op (and safely pairing with a no-op {@link #disarm(String)}) when the name is unresolved;
     * never throws.
     *
     * @param infobaseName the infobase being structurally updated (may be {@code null})
     */
    public static void arm(String infobaseName)
    {
        if (infobaseName == null)
        {
            return;
        }
        IPath wsRoot = ResourcesPlugin.getWorkspace().getRoot().getLocation();
        LaunchUpdateDialogAutoConfirmer.armExclusive(infobaseName,
            () -> StandaloneInfobaseAdmin.restructure(wsRoot, infobaseName));
    }

    /**
     * Disarms the matcher armed by {@link #arm(String)} on the same infobase. Never throws.
     *
     * @param infobaseName the infobase armed with {@link #arm(String)}
     */
    public static void disarm(String infobaseName)
    {
        if (infobaseName != null)
        {
            LaunchUpdateDialogAutoConfirmer.disarmExclusive(infobaseName);
        }
    }

    /**
     * A restructure failure recorded by the matcher, or {@code null} when none. The caller reads
     * this after its update/launch returns and clears it via {@link #clearError()} to turn EDT's bare
     * "cancelled" into the concrete ibcmd failure.
     *
     * @return the last exclusive-lock failure, or {@code null}
     */
    public static String lastError()
    {
        return LaunchUpdateDialogAutoConfirmer.lastExclusiveError();
    }

    /** Clears the recorded exclusive-lock failure after a caller has consumed it. */
    public static void clearError()
    {
        LaunchUpdateDialogAutoConfirmer.clearLastExclusiveError();
    }
}
