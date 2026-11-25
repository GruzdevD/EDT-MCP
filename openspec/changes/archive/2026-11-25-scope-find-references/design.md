# Design

## Context

`FindReferencesTool` currently invokes `MetadataReferenceService` synchronously on the UI thread and renders Markdown. The collector queries the BM reverse-reference index for the target, its produced types, predefined items, and fields, then searches the Xtext index and resolves BSL positions. Its BM branches dereference sources before determining their location. Its BSL path can pass an explicit URI list to the finder, but widens to the workspace if dependency-based enumeration fails. Parts of this collector also serve strict predefined-item deletion checks. See `proposal.md` for motivation and `specs/scoped-reference-search/spec.md` for the behavior contract.

## Goals / Non-Goals

- Make scope enforcement an input constraint of both semantic search branches. Report enough coverage and source identity to compose disjoint blocks.
- Keep the deadline independent of transport and UI waits. Reuse existing job admission and polling rather than building a second job system.
- Do not change the semantics of the shared strict deletion check. Do not add cursor state, subsystem selection, or a new persistent search index.

## Decisions

### 1. Resolve a strict resource plan separately from the target

`sourceScope` is an object with nonempty `projectNames` and optional nonempty `paths`. Paths are a union applied to the named projects: `CommonModules/` matches a directory subtree on segment boundaries, and a path without a trailing slash matches one file. A selector must resolve in at least one selected project; a selector that resolves in one need not resolve in all. Omitting `paths` selects whole `src/` trees, including files immediately below `src/`. Use workspace-relative paths with `/`; reject absolute paths, dot/traversal segments, and malformed or empty keys/lists instead of normalizing them into a broader search. Deduplicate overlapping selectors before traversal. Distinguish an invalid selector from an existing resource whose model/index cannot be examined.

Validate opt-in argument types before the shared MCP dispatcher converts arguments to strings and drops explicit nulls. Use a tool-level opt-in validation hook on the original JSON arguments; leave other tools' argument handling unchanged. In particular, neither `sourceScope: null` nor a JSON string containing an object can stand for a scope, and a quoted `timeoutSeconds` cannot stand for a JSON number. Return the normal MCP tool error before workspace lookup, index waits, or job submission.

Enumerate resource descriptors or index keys to build the plan; this is not a license to load source models, walk source ASTs, or parse out-of-scope files. Resolve the target in `projectName` and augment its semantic target identities only as needed for selected sources. For a base-only scope, extension sources must never be scanned. For an explicitly selected extension, check both references to the original target and those to its adopted identity where applicable. A target-resolution dependency is not a source project.

### 2. Keep BM and Xtext semantic; filter at resource admission

- **BM:** Use read-only, per-source-project model namespaces and a resource-ID-to-workspace-resource mapping. Local EDT SDK inspection shows that BM back-reference records expose namespace and object ID without calling `getObject()`, and resource ID can be derived without loading the object. Verify the project/physical-path mapping on real EDT resources before depending on it: the logical BM URI is not necessarily the physical path, and owner FQN alone cannot distinguish exact files. Match the resource plan before dereferencing a BM source, using the same gate for direct, produced-type, predefined-item, field and adopted-target queries. If lookup by index key cannot prove the mapping or coverage, use a genuinely resource-bounded semantic lookup where supported; otherwise mark the affected block incomplete. Do not use all-source model materialization followed by filtering as a fallback.
- **Xtext:** Use the explicit source-URI finder path, or the per-resource description overload if needed for coverage accounting. Match workspace project and path before sending a URI to the finder or loading a module for position resolution. Cross-check selected index candidates against the authoritative eligible-resource inventory or EDT readiness guarantees; an empty/stale index alone does not prove zero. Verify actual dispatch/load boundaries, including whether coordinate enrichment or resolver callbacks touch excluded sources. Never call the workspace fallback with explicit scope.
- **Safety of shared callers:** Share semantic helpers where useful, but isolate scope, result limits, and new deadline behavior from the strict collector used by existing delete checks. Preserve the old rendering and default search policy when no new parameters are present. Timeout-only calls get structured output but do not acquire a new project restriction.

### 3. Use a search-owned deadline and immutable partial results

Start one monotonic budget at search submission, before index readiness or model access; pass remaining time and cancellation through readiness waits, UI handoffs where needed, BM phases, Xtext monitor, and coordinate enrichment. Do not run an entire long search under UI `syncExec`. Check cancellation between resource units and before publishing a hit. Use a bounded background job; `waitSeconds` only controls how long the client waits before getting a job ID or poll response. `BackgroundJobs` has its own timeout path, so the search deadline must finalize the partial payload as a terminal search result rather than letting generic job timeout discard it. If a platform call cannot be interrupted promptly, publish a frozen snapshot at the deadline and prevent the call from adding late rows or starting new work after it returns. Keep outstanding work bounded until it actually unwinds.

Use the existing argument-dependent response type for the legacy Markdown vs. opt-in JSON split (`sourceScope`, `timeoutSeconds`, or `waitSeconds`). Keep the legacy `limit` hint (`limit*10` internal cap) in legacy mode; the structured mode uses a documented overall reference cap and reports truncation when an extra unique hit would be omitted, not simply because the final count equals the cap. Preserve Markdown for existing poll consumers and deliver a structured job envelope for reference-search jobs, including a structured terminal `result` rather than a JSON string inside Markdown.

### 4. Report completed coverage separately from selected coverage

Return target, requested selectors, effective checked scope, unique examined-resource count, references, `complete`, `stopReason`, model/index state, and elapsed time. Track coverage by canonical `(project, src-relative resource)` keys; count actual examinations, not the size of the planned list, and identify incomplete/unavailable units separately. Compact coverage for large projects, but do not report a whole planned block as checked when only part ran. Use status values distinguishing `completed`, `timeout`, `limit`, `resource_unavailable`, and `index_unavailable`. If a transient not-ready index consumes the available budget, the stop reason is `timeout`, with readiness details; if the index cannot be used without exhausting the budget, report `index_unavailable`.

Use stable occurrence identity for composition: project plus canonical source address, including a concrete BSL offset/fragment or an exact model owner and property/element when available; do not deduplicate distinct BSL occurrences merely because they share a line. Extract real coordinates where available; omit unavailable positions. Capture a real model/index revision if the platform exposes one, otherwise explicitly say unavailable. Recheck for observable change during a scan; a resource-list match or a Git commit is not proof of unchanged EDT model/index state. When no revision is exposed, clients must keep the model/index unchanged for cross-block comparison rather than treating two unavailable IDs as equal.

## Risks / Trade-offs

- BM may store several physical files behind a logical owner. Verify exact-file mapping with configuration, role, subsystem, and extension fixtures. Fail incomplete rather than guessing a path or dropping a source.
- Xtext index enumeration and the wording “resources examined” can mislead: prove completeness against readiness/inventory and count actual per-resource checks. Record model-object resolutions and BSL loads separately in acceptance instrumentation; output filtering alone cannot pass.
- EDT index/model operations may finish after a deadline or race with readiness changes. Use cooperative cancellation and freeze the public payload; never claim completeness after detected invalidation.
- Exact occurrence IDs can differ from legacy grouped rows. Compare structured search results with the same coverage and identity rules, not against the legacy Markdown counts.

## Migration Plan

No data migration. Ship the plugin and bundled guide together; existing callers can keep their old arguments and format. Opt-in clients must inspect job and search completion separately and only merge completed blocks over the same state. A rollback is a plugin rollback; clients must stop sending the new parameters on old versions.
