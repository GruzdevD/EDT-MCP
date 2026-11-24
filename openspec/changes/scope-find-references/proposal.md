# Proposal

## Why

In large EDT projects, `find_references` can stop before covering all sources, and a client cannot resume by searching smaller, independently verifiable blocks. EDT MCP 2.26.1 returned only partial results for two reports in `mom`; neither those counts nor rename previews establish the complete reference set.

## What Changes

- Add an optional `sourceScope` that selects EDT source projects and either their entire `src/` trees or specific directory prefixes and exact files. Apply it before traversing source resources or parsing BSL. `projectName` still resolves the target.
- Add an optional server-side `timeoutSeconds` (1–3600, default 45), including time spent waiting for index readiness. Reuse background jobs and `get_job_status`; MCP wait time does not alter the search budget.
- Return structured coverage, reference locations, model/index state information and completion status for opt-in calls. Preserve the existing search area and Markdown format for legacy calls.
- Document how to search disjoint blocks, merge completed results, and recognize incomplete coverage. No cursor or subsystem filter is required for this change.

## Capabilities

### New Capabilities

- `scoped-reference-search`: semantic metadata and BSL reference search with source-resource selection, a configurable budget, and verifiable coverage.

### Modified Capabilities

## Impact

EDT MCP tool schema, reference collector and EDT index integration, job result delivery, plugin tests, and tool documentation including `get_tool_guide`. No changes to `mom`, «Секатор», or CI; no 1C client/test launches, metadata mutations, text-search substitution, or refactoring-preview substitution.
