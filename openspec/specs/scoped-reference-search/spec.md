# scoped-reference-search Specification

## Purpose

Let EDT MCP clients find semantic references to a metadata object in bounded source blocks and combine completed blocks without mistaking partial results for absence of references.

## Requirements

### Requirement: Independent target and source scope
`find_references` SHALL resolve `objectFqn` in `projectName`. Optional `sourceScope` SHALL select source projects and, within them, whole `src/` trees, directory prefixes, or exact files. The tool SHALL enforce this selection before traversing source model or Xtext resources and before parsing BSL, including when resolving references through adopted objects.

#### Scenario: Search base sources only
- **WHEN** `sourceScope.projectNames` contains only `mom`, and the target has an adopted counterpart in an open extension
- **THEN** no extension resource is traversed as a source and no extension reference is returned

#### Scenario: Search a directory prefix or exact file
- **WHEN** `paths` contains `CommonModules/` and an exact file path relative to `src/`
- **THEN** the resource set is the union of the `CommonModules/` subtree and that file, not similarly named directories or other files

#### Scenario: Search a whole project including files at the root of src
- **WHEN** a valid `sourceScope` selects a project without `paths`
- **THEN** all eligible resources under that project's `src/`, including files directly in `src/`, are in scope

#### Scenario: Reject invalid scope without scanning
- **WHEN** the scope is malformed, has no project names, names an unknown project, or contains an invalid or nonexistent selector
- **THEN** the tool returns an actionable validation error without widening the scope or reporting a successful empty search

### Requirement: Server search budget and job polling
`timeoutSeconds` SHALL be an optional integer in [1, 3600] with default 45 seconds, with or without `sourceScope`. It SHALL bound the search, including waiting for index readiness. Long searches SHALL use background jobs observable with `get_job_status`; MCP wait settings SHALL NOT change the deadline.

#### Scenario: Override the budget without a scope
- **WHEN** a caller supplies `timeoutSeconds: 300` without `sourceScope`
- **THEN** the tool retains the default search area and uses a 300-second server budget

#### Scenario: Reject invalid budget
- **WHEN** `timeoutSeconds` is out of range, fractional, null, or not a number
- **THEN** the tool returns an error that names the parameter and accepted integer range

#### Scenario: Time out while waiting or scanning
- **WHEN** the server budget expires while waiting for the index or while scanning
- **THEN** the terminal search result preserves the references already gathered within the budget, including zero, and reports `complete: false` and `stopReason: "timeout"`

#### Scenario: Poll without resetting the budget
- **WHEN** the initial call returns a job ID and the client polls with a different `waitSeconds`
- **THEN** the poll wait has no effect on the search deadline and the terminal search result is machine-readable through `get_job_status`

### Requirement: Report source addresses and coverage
Opt-in calls SHALL expose a machine-readable target, requested scope, actually checked scope, unique examined-resource count, references, completeness, and stop reason. The result SHALL expose an available model/index state ID or explicitly mark it unavailable. Each reference SHALL identify the source project, type, and a path with available coordinates or an exact model location.

#### Scenario: Return real source locations
- **WHEN** the result contains BSL and model references
- **THEN** BSL rows provide the source path and real line/column where available, and model rows can use the owner's FQN and exact property or element instead
- **AND** unavailable coordinates are not replaced with fabricated positions

#### Scenario: Distinguish empty, truncated and incomplete results
- **WHEN** a valid search returns no references after fully checking its area
- **THEN** it reports `complete: true` and successful completion with an empty list
- **WHEN** a search omits results due to `limit`, or cannot fully check a resource or index
- **THEN** it reports `complete: false` with a reason distinguishing truncation, unavailable resources and unavailable index from each other and from timeout
- **AND** an empty result in those cases is not evidence of absence

### Requirement: Combine completed blocks
For a fixed target and the same unchanged model/index state, the union of completed, untruncated searches over disjoint source-resource blocks SHALL produce the same reference set as one completed search over their combined scope.

#### Scenario: Partition the reference fixture
- **WHEN** a full search and disjoint block searches cover the same fixture resources and model/index state, with references in BSL, a role, a subsystem, the configuration root resource, and an extension
- **THEN** the reference identity sets match exactly, including the extension when selected
- **AND** instrumentation shows that out-of-block model sources were not loaded or traversed and out-of-block BSL resources were not scanned or parsed

### Requirement: Preserve legacy calls
A call without new parameters SHALL preserve the prior search area and Markdown response contract and use the 45-second default server budget.

#### Scenario: Call the original tool contract
- **WHEN** a caller supplies only `projectName`, `objectFqn`, and optionally `limit`
- **THEN** the tool searches its legacy area and returns results in the existing Markdown format with the existing `limit` semantics
