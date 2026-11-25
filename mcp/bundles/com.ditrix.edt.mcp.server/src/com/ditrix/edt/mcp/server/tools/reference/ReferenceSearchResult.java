/**
 * MCP Server for EDT
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.tools.reference;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

import org.eclipse.core.runtime.OperationCanceledException;

import com.ditrix.edt.mcp.server.tools.reference.ReferenceSourcePlan.Address;

/** Publishes immutable, deadline-safe snapshots of the work actually finished by a search. */
public final class ReferenceSearchResult
{
    private static final int COVERAGE_DETAILS_LIMIT = 500;

    record Target(String project, String objectFqn) {}
    record Occurrence(String id, String project, String path, String type, String owner,
        String property, String fragment, Integer listIndex, Integer offset, Integer line, Integer column) {}
    record Snapshot(Target target, Map<String, Object> requestedScope, List<Address> effectiveCheckedScope,
        int examinedResourceCount, boolean checkedScopeTruncated, List<String> unavailable,
        int unavailableCount, List<Occurrence> references, boolean complete, String stopReason,
        String stateId, long elapsedMillis, Map<String, Integer> bmSourceReads,
        Map<String, Integer> bslSourceLoads) {}

    private final Target target;
    private final Map<String, Object> scope;
    private final int limit;
    private final LongSupplier clock;
    private final long startedAt;
    private final long budgetNanos;
    private final Set<Address> examined = new LinkedHashSet<>();
    private final Map<String, Occurrence> references = new LinkedHashMap<>();
    private final Set<String> unavailable = new LinkedHashSet<>();
    private final Map<String, Integer> bmSourceReads = new LinkedHashMap<>();
    private final Map<String, Integer> bslSourceLoads = new LinkedHashMap<>();
    private boolean waitingForIndex;
    private boolean indexUnavailable;
    private boolean limitReached;
    private Snapshot terminal;

    public ReferenceSearchResult(String project, String fqn, ReferenceSourceScope scope,
        int limit, int timeoutSeconds, long startedAt)
    {
        this(project, fqn, scope, limit, timeoutSeconds, System::nanoTime, startedAt);
    }

    ReferenceSearchResult(String project, String fqn, ReferenceSourceScope scope,
        int limit, int timeoutSeconds, LongSupplier clock)
    {
        this(project, fqn, scope, limit, timeoutSeconds, clock, clock.getAsLong());
    }

    private ReferenceSearchResult(String project, String fqn, ReferenceSourceScope scope,
        int limit, int timeoutSeconds, LongSupplier clock, long startedAt)
    {
        this.target = new Target(project, fqn);
        this.scope = scope == null ? Map.of() : scope.selection();
        this.limit = limit;
        this.clock = clock;
        this.startedAt = startedAt;
        this.budgetNanos = TimeUnit.SECONDS.toNanos(timeoutSeconds);
    }

    public long remainingMillis()
    {
        long remaining = budgetNanos - (clock.getAsLong() - startedAt);
        return Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remaining + 999_999L));
    }

    private boolean expired()
    {
        return clock.getAsLong() - startedAt >= budgetNanos;
    }

    synchronized boolean stopped()
    {
        return terminal != null || limitReached || expired() || Thread.currentThread().isInterrupted();
    }

    void check()
    {
        if (stopped())
        {
            throw new OperationCanceledException();
        }
    }

    synchronized void waitingForIndex(boolean waiting)
    {
        if (!stopped())
        {
            waitingForIndex = waiting;
        }
    }

    synchronized void resourceUnavailable(String detail)
    {
        if (!stopped())
        {
            unavailable.add(detail);
        }
    }

    synchronized void indexUnavailable(String detail)
    {
        if (!stopped())
        {
            indexUnavailable = true;
            unavailable.add(detail);
        }
    }

    synchronized void invalidate(String detail)
    {
        if (!stopped())
        {
            examined.clear();
            indexUnavailable(detail);
        }
    }

    synchronized void examined(Address address)
    {
        if (!stopped())
        {
            examined.add(address);
        }
    }

    synchronized void sourceLoad(String project, boolean bsl)
    {
        check();
        (bsl ? bslSourceLoads : bmSourceReads).merge(project, 1, Integer::sum);
    }

    synchronized void add(Occurrence occurrence)
    {
        check();
        if (!references.containsKey(occurrence.id()))
        {
            if (references.size() == limit)
            {
                limitReached = true;
                throw new OperationCanceledException();
            }
            references.put(occurrence.id(), occurrence);
        }
    }

    public synchronized Object timeout()
    {
        return freeze("timeout");
    }

    synchronized Snapshot finish()
    {
        if (expired())
        {
            return freeze("timeout");
        }
        if (limitReached)
        {
            return freeze("limit");
        }
        if (indexUnavailable)
        {
            return freeze("index_unavailable");
        }
        return freeze(unavailable.isEmpty() ? "completed" : "resource_unavailable");
    }

    private Snapshot freeze(String reason)
    {
        if (terminal != null)
        {
            return terminal;
        }
        if (waitingForIndex && "timeout".equals(reason))
        {
            unavailable.add("Waiting for the EDT model and reference index");
        }
        List<Address> checked = new ArrayList<>(examined);
        checked.sort(Comparator.comparing(Address::project).thenComparing(Address::path));
        long elapsed = Math.max(0L, clock.getAsLong() - startedAt);
        terminal = new Snapshot(target, scope, checked.stream().limit(COVERAGE_DETAILS_LIMIT).toList(),
            examined.size(), checked.size() > COVERAGE_DETAILS_LIMIT,
            unavailable.stream().limit(COVERAGE_DETAILS_LIMIT).toList(), unavailable.size(),
            List.copyOf(references.values()), "completed".equals(reason), reason, "unavailable",
            TimeUnit.NANOSECONDS.toMillis(Math.min(budgetNanos, elapsed)), Map.copyOf(bmSourceReads),
            Map.copyOf(bslSourceLoads));
        return terminal;
    }
}
