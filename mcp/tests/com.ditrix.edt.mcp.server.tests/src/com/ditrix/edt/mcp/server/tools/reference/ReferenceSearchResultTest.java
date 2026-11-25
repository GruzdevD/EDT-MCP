/**
 * MCP Server for EDT
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.tools.reference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.eclipse.core.runtime.OperationCanceledException;
import org.junit.Test;

import com.ditrix.edt.mcp.server.tools.reference.ReferenceSearchResult.Occurrence;
import com.ditrix.edt.mcp.server.tools.reference.ReferenceSourcePlan.Address;

public class ReferenceSearchResultTest
{
    @Test
    public void deadlineWhileWaitingForIndexFreezesZeroHits()
    {
        var clock = new AtomicLong();
        var scope = ReferenceSourceScope.parse(Map.of("projectNames", List.of("Base")));
        for (ReferenceSourceScope selection : new ReferenceSourceScope[]{null, scope})
        {
            clock.set(0);
            var search = new ReferenceSearchResult("Base", "Catalog.Catalog", selection, 100, 45, clock::get);
            search.waitingForIndex(true);
            clock.set(TimeUnit.SECONDS.toNanos(44));
            assertFalse(search.stopped());
            clock.set(TimeUnit.SECONDS.toNanos(45));
            assertTrue(search.stopped());
            var result = search.finish();
            assertEquals("timeout", result.stopReason());
            assertEquals(45000, result.elapsedMillis());
            assertEquals(0, result.references().size());
            assertEquals(1, result.unavailableCount());
        }
    }

    @Test
    public void deadlineAfterHitsRejectsLateWritesAndKeepsPartialCoverage()
    {
        var clock = new AtomicLong();
        var search = new ReferenceSearchResult("Base", "Catalog.Catalog", null, 100, 1, clock::get);
        Address address = new Address("Base", "Root.bsl");
        search.add(hit(address, "first"));
        search.examined(address);
        clock.set(TimeUnit.SECONDS.toNanos(1));
        Object frozen = search.timeout();
        assertThrows(OperationCanceledException.class, () -> search.add(hit(address, "late")));
        search.examined(new Address("Base", "Late.bsl"));
        assertSame(frozen, search.finish());
        assertEquals(1, search.finish().references().size());
        assertEquals(List.of(address), search.finish().effectiveCheckedScope());
    }

    @Test
    public void capDistinguishesExactCountAndExtraOccurrence()
    {
        Address base = new Address("Base", "A.bsl");
        Address extension = new Address("Extension", "A.bsl");
        var exact = new ReferenceSearchResult("Base", "Catalog.Catalog", null, 3, 45, () -> 0);
        var first = hit(base, "one");
        exact.add(first);
        exact.add(first);
        exact.add(hit(base, "two"));
        exact.add(hit(extension, "one"));
        assertTrue(exact.finish().complete());
        assertEquals(3, exact.finish().references().size());

        var truncated = new ReferenceSearchResult("Base", "Catalog.Catalog", null, 1, 45, () -> 0);
        truncated.add(first);
        truncated.add(first);
        assertThrows(OperationCanceledException.class, () -> truncated.add(hit(base, "two")));
        assertEquals("limit", truncated.finish().stopReason());
        assertEquals(List.of(first), truncated.finish().references());
    }

    @Test
    public void distinguishesResourceAndIndexFailure()
    {
        var resource = new ReferenceSearchResult("Base", "Catalog.Catalog", null, 1, 45, () -> 0);
        resource.resourceUnavailable("Missing physical file");
        assertEquals("resource_unavailable", resource.finish().stopReason());

        var index = new ReferenceSearchResult("Base", "Catalog.Catalog", null, 1, 45, () -> 0);
        index.examined(new Address("Base", "Root.bsl"));
        index.invalidate("Observed a source change");
        assertEquals("index_unavailable", index.finish().stopReason());
        assertEquals(0, index.finish().examinedResourceCount());
    }

    private static Occurrence hit(Address address, String fragment)
    {
        return new Occurrence(address.uri() + "#" + fragment, address.project(), address.path(), "bsl",
            null, "symbol", fragment, null, null, 12, null);
    }
}
