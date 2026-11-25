/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils.snapshot;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.List;

import org.junit.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

public class SnapshotSourcePolicyTest
{
    @Test
    public void reviewAccessSurvivesExecutionFlagChangesWhileJobsRequireCurrentGrant() throws IOException
    {
        var read = SnapshotSourcePolicy.grants(policy("C:\\tree\\Allowed"), "Allowed", List.of());
        var original = read.get(0);
        var execute = List.of(new SnapshotSourcePolicy.Grant(
            original.name(), original.location(), original.extensions(), true));
        SnapshotSourcePolicy.requireGrantScope(read, execute, false);
        SnapshotSourcePolicy.requireGrantScope(execute, read, false);
        SnapshotSourcePolicy.requireGrantScope(execute, execute, true);
        assertThrows(IOException.class, () -> SnapshotSourcePolicy.requireGrantScope(execute, read, true));
        var relocated = SnapshotSourcePolicy.grants(policy("C:\\other\\Allowed"), "Allowed", List.of());
        assertThrows(IOException.class, () -> SnapshotSourcePolicy.requireGrantScope(read, relocated, false));
    }
    @Test
    public void arbitrarySourceReadingDoesNotGrantTestExecution() throws IOException
    {
        String ordinary = policy("C:\\tree\\Allowed");
        assertFalse(SnapshotSourcePolicy.grants(ordinary, "Allowed", List.of()).get(0).yaxunit());
        String enabled = ordinary.replace("\"extensions\"", "\"yaxunit\":true,\"extensions\"");
        assertTrue(SnapshotSourcePolicy.grants(enabled, "Allowed", List.of()).get(0).yaxunit());
        assertThrows(IOException.class, () -> SnapshotSourcePolicy.grants(
            enabled.replace("true", "\"true\""), "Allowed", List.of()));
    }

    @Test
    public void deniesByDefaultBeforeLookingUpWorkspace()
    {
        assertThrows(IOException.class, () -> SnapshotSourcePolicy.require(null, policy("C:\\tree\\Allowed"),
            "Allowed", List.of()));
        assertThrows(IOException.class, () -> SnapshotSourcePolicy.grants(null, "Allowed", List.of()));
    }

    @Test
    public void doesNotAuthorizeNeighborOrBroadenSourceRootAndTypes()
        throws IOException
    {
        String policy = policy("C:\\tree\\Allowed");
        assertThrows(IOException.class, () -> SnapshotSourcePolicy.grants(policy, "Neighbor", List.of()));
        assertThrows(IOException.class, () -> SnapshotSourcePolicy.grants(policy, "Allowed", List.of("Neighbor")));
        SnapshotSourcePolicy.Grant grant = SnapshotSourcePolicy.grants(policy, "Allowed", List.of()).get(0);
        assertEquals("src/Item.MDO", grant.projectPathInRepository("Allowed/", "Allowed/src/Item.MDO"));
        assertNull(grant.projectPathInRepository("Allowed/", "Neighbor/src/Item.bsl"));
        assertNull(grant.projectPathInRepository("Allowed/", "AllowedOther/src/Item.bsl"));
        assertTrue(grant.permits("src/ОбщийМодуль/Module.bsl"));
        assertFalse(grant.permits("src/../Neighbor/src/Module.bsl"));
        assertFalse(grant.permits("src/Item.bsl:stream.bsl"));
        assertFalse(grant.permits("src/.git/Item.bsl"));
        assertFalse(grant.permits("src/private.xml"));
        assertFalse(grant.permits("DT-INF/Item.bsl"));
    }

    @Test
    public void refusesNetworkAndDevicePathsWithoutEchoingThem()
    {
        for (String path : List.of("\\\\server\\share\\Allowed", "\\\\?\\C:\\Allowed", "C:Allowed"))
        {
            IOException e = assertThrows(IOException.class,
                () -> SnapshotSourcePolicy.grants(policy(path), "Allowed", List.of()));
            assertFalse(e.getMessage().contains(path));
        }
    }

    private static String policy(String path)
    {
        JsonObject grant = new JsonObject();
        grant.addProperty("location", path);
        JsonArray extensions = new JsonArray();
        extensions.add("bsl");
        extensions.add("mdo");
        grant.add("extensions", extensions);
        JsonObject root = new JsonObject();
        root.add("Allowed", grant);
        return root.toString();
    }
}
