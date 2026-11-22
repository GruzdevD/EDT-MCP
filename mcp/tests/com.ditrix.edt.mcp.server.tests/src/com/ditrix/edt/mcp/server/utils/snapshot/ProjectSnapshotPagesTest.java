/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils.snapshot;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.ditrix.edt.mcp.server.utils.snapshot.ProjectSnapshotCapture.ModelStatus;
import com.ditrix.edt.mcp.server.utils.snapshot.ProjectSnapshotCapture.Snapshot;
import com.ditrix.edt.mcp.server.utils.snapshot.ProjectSnapshotPages.View;
import com.ditrix.edt.mcp.server.utils.snapshot.ProjectSnapshotRegistry.Limits;
import com.ditrix.edt.mcp.server.utils.snapshot.ProjectSnapshotRegistry.Retained;
import com.ditrix.edt.mcp.server.utils.snapshot.ProjectSourceManifest.Entry;
import com.ditrix.edt.mcp.server.utils.snapshot.ProjectSourceManifest.Project;
import com.ditrix.edt.mcp.server.utils.snapshot.ProjectSourceManifest.Version;
import com.ditrix.edt.mcp.server.utils.snapshot.SnapshotSourcePolicy.Grant;

public class ProjectSnapshotPagesTest
{
    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void readsOldBytesAfterDiskChangesAndComparesWithTheNewSnapshot() throws IOException
    {
        Path root = temp.newFolder().toPath();
        Path file = Files.writeString(root.resolve("Module.bsl"), "first captured line\n");
        List<Grant> scope = List.of(new Grant("Configuration", root.toString(), Set.of("bsl", "mdo")));
        ProjectSnapshotRegistry<String> snapshots = new ProjectSnapshotRegistry<>(Clock.systemUTC(),
            new Limits(Duration.ofMinutes(1), 2, 2, 100_000));
        Retained old = snapshots.retain("client", scope, snapshot(Files.readAllBytes(file)));
        ProjectSnapshotPages pages = new ProjectSnapshotPages();
        var first = pages.read(old, null, View.DIFF, null, null, 1);
        assertEquals("src/Config.mdo", first.entries().get(0).path());
        assertNotNull(first.nextCursor());

        Files.writeString(file, "later captured line\n");
        Retained latest = snapshots.retain("client", scope, snapshot(Files.readAllBytes(file)));
        var second = pages.read(old, null, View.DIFF, null, first.nextCursor(), 1);
        assertNull(second.nextCursor());
        var row = second.entries().get(0);
        assertEquals("src/Module.bsl", row.path());
        assertTrue(row.diffs().get(0).complete());
        assertTrue(row.diffs().get(0).patch().contains("+first captured line"));
        assertFalse(row.diffs().get(0).patch().contains("later captured line"));

        var compare = pages.read(latest, old, View.DIFF, null, null, 1);
        assertEquals(1, compare.total());
        var delta = compare.entries().get(0).diffs().get(0);
        assertTrue(delta.patch().contains("-first captured line"));
        assertTrue(delta.patch().contains("+later captured line"));
        assertThrows(IOException.class, () -> pages.read(latest, null, View.DIFF, null, first.nextCursor(), 1));
        assertThrows(IOException.class, () -> pages.read(old, null, View.MANIFEST, null, first.nextCursor(), 1));
        assertThrows(IOException.class, () -> pages.read(latest, null, View.DIFF, row.entryId(), null, 1));
    }

    @Test
    public void reportsIncompleteDiffWithItsReason() throws IOException
    {
        Retained retained = new Retained(UUID.randomUUID(), Clock.systemUTC().instant(), List.of(), snapshot(new byte[140_000]));
        var page = new ProjectSnapshotPages().read(retained, null, View.DIFF, null, null, 10);
        var delta = page.entries().get(1).diffs().get(0);
        assertFalse(delta.complete());
        assertNull(delta.patch());
        assertEquals("inputLimit", delta.reason());
    }

    private static Snapshot snapshot(byte[] module)
    {
        Version head = new Version("before\n".getBytes(StandardCharsets.UTF_8));
        Version staged = new Version("updated\n".getBytes(StandardCharsets.UTF_8));
        Project project = new Project("Configuration", "refs/heads/main", "head", List.of(
            new Entry("src/Config.mdo", head, staged, staged), new Entry("src/Module.bsl", null, null, new Version(module))),
            false);
        return new Snapshot(List.of(project), Map.of(), ModelStatus.UNKNOWN);
    }
}
