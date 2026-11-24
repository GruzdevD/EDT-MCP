# Tasks

## 1. Scope contract and resource inventory

- [x] 1.1 Add `sourceScope` and strict selector validation to the MCP schema and bundled `find_references` guide. Add an opt-in MCP validation hook before argument conversion, with no change for other tools. At the protocol boundary, test that explicit `null` and JSON-in-a-string scopes fail without search dispatch. Test whole-project, exact-file, directory-prefix, root-file, overlapping-selector, and malformed-input cases, including unknown project and nonexistent selector with no traversal.
- [ ] 1.2 Verify on the EDT target platform how BM namespace and resource ID map to a physical `src/` resource before source dereference. Capture evidence for configuration, role, subsystem, and extension resources, including exact-file matching and the behavior when mapping is unavailable.
- [ ] 1.3 Build a shared resource plan from the chosen projects and paths; test that path resolution and inventory do not load source objects or parse BSL and that an unresolved or inaccessible resource cannot silently widen the plan.

## 2. Scoped semantic collectors

- [ ] 2.1 Apply the plan at BM source admission for direct, produced-type, predefined-item, field, and adopted-target queries. Test each branch with instrumented source dereferences and an out-of-scope extension; preserve strict delete-check behavior in plugin regression tests without running deletion on live metadata.
- [ ] 2.2 Pass only admitted URI/description inputs to the Xtext finder and BSL location resolver. Test actual dispatch and parse counts, no workspace fallback, and incomplete coverage for a missing or stale selected resource/index; verify that a valid indexed scope with zero hits completes normally.
- [ ] 2.3 Emit canonical occurrence identity, real source addresses, examined-resource coverage, and a state ID or explicit unavailability. Test same-line BSL hits, identical paths in different projects, precise model owner/property addresses, partial coverage, and unavailable coordinates. Document the result fields and state caveat in `get_tool_guide`.

## 3. Deadline, limits, and job delivery

- [ ] 3.1 Implement the 1–3600 second server budget, including index readiness, with a 45-second default and validation before starting work. Use the opt-in MCP hook to reject explicit `null` and quoted numbers before argument conversion; test both at the protocol boundary. Use controlled-clock tests for the default with and without scope, explicit override without scope, invalid values, timeout with zero hits while waiting for the index, and timeout after hits while scanning. Document budget and timeout semantics in `get_tool_guide`.
- [ ] 3.2 Wire the shared job system and structured `get_job_status` terminal results. Test that changing `waitSeconds` does not reset or alter the deadline, that results survive a timeout even if platform work unwinds late, and that existing poll consumers retain their response format. Document polling in the bundled guides.
- [ ] 3.3 Wire structured stop reasons and overall limits; test exact cap versus actual truncation and distinguish `completed`, `timeout`, `limit`, `resource_unavailable`, and `index_unavailable`. Retain and test the old invocation's scope, Markdown layout, and limit semantics. Add legacy, timeout-only, scoped, and poll examples to the tool guide and reference documentation.

## 4. End-to-end acceptance

- [ ] 4.1 Run the plugin test suite and a read-only EDT MCP fixture search. Assert independent control references in BSL, a role, a subsystem, the configuration root resource, and an extension; compare the full structured search against the union of completed disjoint blocks over the same unchanged model/index. Assert source-load/parse counters exclude out-of-block resources, especially extension sources in a base-only block. Include an exact file directly under `src/` in the partition coverage test.
- [ ] 4.2 If local `mom` is available through the updated EDT MCP, run read-only block searches for `Report.РегламентированныйОтчетНДПИ` and `Report.РегламентированныйОтчетНедра` with `sourceScope.projectNames: ["mom"]`. Report actual selectors, durations, terminal statuses and known configuration, role, and subsystem references as checkpoints. Record any skipped or incomplete block without claiming full results. Do not change `mom` or «Секатор», run the 1C client or 1C tests, or rename or delete metadata.
- [ ] 4.3 Verify the implementation stays within the plugin, its tests and documentation, with no CI changes. Report changed files, test outcomes, and verified MCP call examples; label unexecuted scenarios and examples explicitly.
