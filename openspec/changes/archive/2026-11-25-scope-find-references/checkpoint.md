# Checkpoint — `scope-find-references`

## 2026-11-25 — full Tycho archive check

The final implementation ran the full Tycho suite under Axiom JDK 25. The suite reported 7,420 tests, seven failures, one error and ten skips. Every failing test ID was reproduced on baseline `62970dc9`. The final log is `archive-gate-tycho.log` under `C:/Users/basko/.codex/edt-scope-probe/build`. The baseline records are `maven-verify-baseline.log` and `maven-verify-baseline-additional.log` in the same directory.

## 2026-11-25 — corrections from independent review

- The default structured search compares physical BSL files in the dependency projects with the scanned Xtext candidates. It checks the reference descriptions, including occurrence multiplicity, before and after scanning. The explicit-scope search checks the same index data for each admitted file.
- BM results exclude references from generated containment. Resource-ID collisions involving selected files make coverage incomplete. Collisions wholly outside the selection are omitted before source dereference.
- Explicit extension selection checks both the persistent nature and the runtime relationship to the base. An unresolved relationship makes coverage incomplete. BSL target resolution includes the legacy produced-type wrapper URIs. The worker verifies the set of matching selectors in each project before and after waiting for EDT and after scanning.
- Project and path resolution runs inside the bounded job. An asynchronous caller can inspect validation errors using the returned job ID. Legacy Markdown runs the original collector under its time budget without waiting for workspace validation. The two reference pages were regenerated from the live guides.

The partition fixture on `127.0.0.1:8872` returned 20 catalog occurrences across 48 examined resources at both the standard cap of 100 and an explicit cap of 500. The combined occurrence IDs matched the two project blocks. The role, subsystem, configuration, root-file and same-line BSL controls passed. The loose `src/Root.bsl` reported `index_unavailable` in both the timeout-only default search and the exact-file search. The default search examined 34 other resources and the exact search examined zero resources.

On the original fixture copies, all 17 read-only E2E tests and all four documented examples passed. The new asynchronous validation case confirms that unknown projects and missing selectors produce a failed job with an actionable message. The stand is running the corrected bundle with the original fixture copies.

The run records are `review-partition.json`, `review-missing-bsl.json` and `review-original-fixtures.json` under `C:/Users/basko/.codex/edt-scope-probe/build`. Strict OpenSpec validation passed.

The targeted Tycho run passed all 299 tests and built the p2 repository. The full suite ran 7,416 tests. Its seven failures, one error and ten skips matched the previous run, and the eight failing test IDs had already been reproduced on `62970dc9`. See `review-focused-final.log` and `review-full.log` in the same build directory.

The follow-up review found four edge cases. The source plan now checks selected directories against the physical tree. Scoped BSL coverage requires resolved adopted targets for related extensions, and missing declared produced types make the relevant target expansion unavailable. Structured calls wait on the model and reference index while EDT validation can continue independently. Legacy searches and strict deletion keep their existing readiness policies.

The partition checks passed again. In a scratch copy with Eclipse auto-refresh disabled, creating a BSL file on disk caused both the directory-scoped and default structured calls to report `resource_unavailable`. The exact path still failed workspace selector validation until refresh. The temporary file was removed. See `review-unrefreshed-bsl.json` in the build directory.

The final inventory check covers each selector in every chosen project, including handles without a workspace member. In the two-project test, the same file and directory selectors both completed while the extension had no matching resources. Both then reported `resource_unavailable` after a matching file and directory appeared in the extension before refresh. The temporary fixture was removed. See `review-unrefreshed-bsl-shared.json` in the build directory. The independent Astra 6.2 High reviewer confirmed that all ten original and all four follow-up findings were addressed.

The final bundle passed all 17 read-only E2E tests, the four guide examples, the partition comparison and both missing-index cases. The final targeted Tycho run passed 303 tests and built the p2 repository. See `review-final-focused.log` in the build directory. The stand remains on port 8872 with the original fixture copies.

## 2026-11-25 — scoped search implementation and acceptance

The branch implements independent source selection, BM and Xtext admission, canonical occurrences, coverage, a submission-to-completion deadline, and structured job polling. Legacy calls preserve the Markdown format and `limit*10` collection hint. The strict metadata deletion collector uses its original source policy.

### Changed files

- The search implementation lives in `ReferenceSourcePlan.java`, `ScopedReferenceSearch.java`, and `ReferenceSearchResult.java`. `FindReferencesTool.java`, `ReferenceSourceScope.java`, `MetadataReferenceService.java`, and `BslReferenceSearch.java` connect it to tool dispatch and preserve the existing collection paths.
- The protocol and polling changes are in `JsonRpcRequest.java`, `McpProtocolHandler.java`, `BackgroundJobs.java`, `BackgroundJobRenderer.java`, and `GetJobStatusTool.java`. The two bundle manifests declare the Java packages used by the implementation and tests.
- The Java tests cover source scope and plan, scoped admission, deadline snapshots, wire validation, and job polling. The read-only fixture cases extend `tests/e2e/tools/test_find_references.py`. Both tools have updated bundled and reference guides. The changed paths are limited to the plugin, its tests, documentation, and OpenSpec records.

### Live fixture results

The isolated Windows EDT `2026.2.2.21` served both fixture projects through `127.0.0.1:8872`. The acceptance workspace used copies prepared offline with an explicit subsystem member, the `src/Configuration.distr` control, an extension role reference to the adopted catalog, and two same-line BSL usages of that catalog. The run used read-only MCP calls.

| Search | Completion and evidence |
| --- | --- |
| Whole base and extension for `Catalog.Catalog`, `limit: 500` | `completed`, 48 examined resources and 102 occurrences. The union of the two completed project blocks matched exactly by ID (51 occurrences each). Controls included `Configuration/Configuration.mdo`, `Subsystems/Subsystem/Subsystem.mdo`, extension `Roles/tests_DefaultRole/Rights.rights`, and the extension BSL module. |
| Three exact base files for `Catalog.Catalog` | `completed`, three resources and two occurrences. The result matched the union of the configuration, subsystem, and `Configuration.distr` blocks. The file directly under `src/` was fully examined and returned zero occurrences. |
| Base-only, extension-only, and combined for `CommonModule.Calc` | `completed` for all three. Two base occurrences and three extension occurrences matched the five combined IDs. The base-only run recorded zero source reads and zero BSL loads in the extension. The exact extension BSL block loaded one file and found the adopted Calc call. |
| Same-line catalog usages in the extension BSL module | `completed`, two distinct occurrence IDs at line 96, columns 12 and 42. |
| Limit of one on one exact file, then on two files | One occurrence with `completed` for the first call. One retained occurrence with `limit` for the second. A separate loose `src/Root.bsl` without an EDT resource description gave `index_unavailable` and zero examined resources. |

Raw fixture evidence is in `C:/Users/basko/.codex/edt-scope-probe/build/partition-acceptance.json`.

The updated bundle also passed 16 read-only `find_references` E2E cases with the original fixture copies. The four verified documentation examples were legacy Markdown, the default search area with `timeoutSeconds: 300`, an exact extension BSL source, and polling that search by job ID. The served `get_tool_guide` content was checked. Evidence is in `C:/Users/basko/.codex/edt-scope-probe/build/documented-examples.json`.

### Plugin suite

The full Maven/Tycho `verify` run executed 7,411 tests under the installed Axiom JDK 25. It ended with 7 failures, 1 error and 10 skipped tests. Every failing test ID was reproduced on the original branch commit `62970dc9` using the same toolchain. Those failures are in tool registry metadata, Vanessa artifact classification, merge-rule XML depth, and the pre-launch tracker. The new scope, budget and polling tests passed. All 96 strict deletion regression tests passed. A focused direct JUnit run passed all 121 tests.

The targeted Maven/Tycho `verify` run passed 257 tests and assembled the local p2 repository. The full suite log is `C:/Users/basko/.codex/edt-scope-probe/build/maven-verify-final.log`. The baseline logs are `maven-verify-baseline.log` and `maven-verify-baseline-additional.log`. The targeted run is in `maven-verify-reference-tests.log` in the same directory. The stand remains on port 8872 with the updated bundle and the original fixture copies.

### Local `mom` check

The updated MCP endpoint listed only `TestConfiguration` and `TestConfiguration.tests`. The two optional searches with `projectName: "mom"` and `sourceScope.projectNames: ["mom"]` were skipped because `mom` was unavailable on the updated stand. This applies to both `Report.РегламентированныйОтчетНДПИ` and `Report.РегламентированныйОтчетНедра`. There are no measured durations, terminal states or reference counts for these calls.

## 2026-11-25 — EDT fixture evidence for task 1.2

Verified on Windows with EDT `2026.2.2.21` and MCP `3.0.0` built from `62970dc9` in the `feat/upgrade-find-references` worktree. The stand uses its own EDT copy, user directory, workspaces and fixture copies under `C:/Users/basko/.codex/edt-scope-probe`, with MCP on `127.0.0.1:8872`. Both `TestConfiguration` and `TestConfiguration.tests` reported `ready`. The committed subsystem fixture has no members, so the diagnostic run added `<content>Catalog.Catalog</content>` to its separate fixture copy before importing it. No live metadata writes or 1C client launches were used.

**Mapping verified on real back-reference records.** Inventory accessible physical `IFile` handles under each project's `src/` and call `IQualifiedNameFilePathConverter.getFqn(IFile)`. In one platform read-only transaction, resolve each name with `IBmPlatformTransaction.getTopObjectIdByFqn(namespace, fqn)` and use `namespace.asEngine().getObjectResourceId(topObjectId)` to index the file by `(namespace, resourceId)`. Match an `IBmCrossReference` via its `getNamespace()` and `getObjectId()` with the same resource-ID conversion. The probe applied `ReferenceSourceScope.includes` to the resulting project and exact `src/`-relative path. The mapping code made zero source `getObject()` calls. The subsequent audit dereferenced only the four admitted controls and independently confirmed each path through EDT's `IResourceLookup.getPlatformResource(EObject)`.

| Namespace | Target / reference feature | Source object ID | Resource ID | Confirmed exact path under `src/` | Exact selector rejected |
| --- | --- | ---: | ---: | --- | --- |
| `TestConfiguration` | `Catalog.Catalog` / `catalogs` | `4294967296` | `1` | `Configuration/Configuration.mdo` | Same path in the extension |
| `TestConfiguration` | `Catalog.Catalog` / `content` | `30064771072` | `7` | `Subsystems/Subsystem/Subsystem.mdo` | `Subsystems/Subsystem/CommandInterface.cmi` |
| `TestConfiguration.tests` | `Catalog.tests_ExtOnly` / `object` | `47244640257` | `11` | `Roles/tests_DefaultRole/Rights.rights` | `Roles/tests_DefaultRole/tests_DefaultRole.mdo` |
| `TestConfiguration.tests` | `Catalog.tests_ExtOnly` / `catalogs` | `4294967296` | `1` | `Configuration/Configuration.mdo` | Same path in the base project |

- The base and extension configuration files shared resource ID `1`. The role description and rights files had distinct BM names (`Role.tests_DefaultRole` and `Role.tests_DefaultRole.Rights`) and resource IDs (`4` and `11`). The rights reference gave a null `bmGetUri()` even after dereference, while EDT resolved the physical rights file.
- With the real `(TestConfiguration.tests, 11)` entry withheld from the diagnostic inventory, the probe reported `resource_unavailable` and made zero source dereferences for that check. A live base `commandSources` record also had no physical candidate (`objectId=69`, `resourceId=0`) and was left unread. Classify a source as out of scope only when the platform can place it outside the selection. Report incomplete coverage for unresolved or ambiguous mappings. BSL files returned `BM_NULL_ID` in this lookup and still require the Xtext path.
- Local evidence is in `build/probe-evidence.tsv` and `build/probe-response.json` under the stand directory. `build/BmScopeProbe.java` is the diagnostic source. `build/Restart-ScopeStand.ps1 -Probe` and `build/Read-ScopeStand.ps1 -Probe` rerun it with the retained diagnostic bundle and separate fixture copies. Restart without `-Probe` for the ordinary branch build on the same port.

Task 1.2 is complete. Task 1.3 starts with the verified path-to-key join. Plan how to distinguish a proven out-of-scope resource from a partial inventory and how to detect duplicate keys and invalidation before claiming coverage. This test covered the listed fixture resources on EDT 2026.2.2.21. Coverage across other EDT builds, platforms, or resource kinds is still open.

## Historical handoff from 2026-11-24

Recorded **2026-11-24 16:11:46 +0300 (MSK)**. Repository: `/Users/vgolenya/git/EDT-MCP`. Branch: `github-fork`, tracking `origin/github-fork`. HEAD and the local upstream ref both pointed to `f482dc507c9f80a865c2621b3ac95b6f08034aff`. This is a local handoff; no commit, push, agent launch, or context compaction was done.

## Goal and stopping point

Make semantic `find_references` searches composable by independently selected source blocks without treating partial results as evidence of absence. This is the only active OpenSpec change, `spec-driven`, `ready`, **1/12 tasks complete**. Read [proposal.md](proposal.md), [design.md](design.md), [spec.md](specs/scoped-reference-search/spec.md), and [tasks.md](tasks.md). No other OpenSpec change was involved.

Current task, [1.2, line 6](tasks.md#L6): “Verify on the EDT target platform how BM namespace and resource ID map to a physical `src/` resource before source dereference. Capture evidence for configuration, role, subsystem, and extension resources, including exact-file matching and the behavior when mapping is unavailable.” Static SDK inspection confirmed how to obtain the BM key. No physical-file mapping has been proved on a running fixture, and no live probe was installed. Work stopped after checking the available EDT MCP instance and asking the user, in Russian, whether there is an isolated EDT with `TestConfiguration` and its test extension or whether to prepare a separate local copy. The user requested this handoff, not a stand selection. Task 1.2 remains unchecked; task 1.3 and later have not started.

## Requirements and agreements

- [Spec: Independent target and source scope](specs/scoped-reference-search/spec.md#L9), [design decisions 1–2](design.md#L15): the target is resolved in `projectName`; source projects and exact `src/` files or directory prefixes are independent. Admit sources before BM dereference, Xtext dispatch or BSL parsing. A base-only plan must not load extension sources; an explicitly selected extension can reference the original and adopted target. No output-only filtering, workspace fallback for explicit scope, or substitution with text search or refactoring previews. Leave strict deletion checks unchanged.
- [Spec: budget, coverage, and legacy calls](specs/scoped-reference-search/spec.md#L28), [design decisions 3–4](design.md#L29): 1–3600 seconds (default 45) including index wait; preserve partial results in shared job polling; polling wait does not reset the deadline. Structured opt-in results report actual coverage and stop reason. Keep original source area, Markdown and `limit*10` semantics for legacy calls. These changes are still pending.
- The user approved a tool-level opt-in validation hook before JSON arguments are flattened. [Design decision 1](design.md#L19) and [tasks 1.1/3.1](tasks.md) were revised; the proposal and spec were not edited. Scope validation is implemented; strict `timeoutSeconds` typing (including explicit `null` and quoted numbers) belongs to 3.1.
- [Proposal: Impact](proposal.md#L22), [tasks 4.1–4.3](tasks.md#L21): plugin, tests, and docs only; no CI changes. No changes to `mom` or «Секатор», no 1C client or 1C tests, no metadata rename/delete or other live metadata mutations. If `mom` is available through the **updated** MCP, later run read-only blocks for `Report.РегламентированныйОтчетНДПИ` and `Report.РегламентированныйОтчетНедра` with `sourceScope.projectNames: ["mom"]`; record selectors, elapsed times, terminal statuses and configuration/role/subsystem checkpoints. Label skipped or incomplete runs. Do not treat 2.26.1 counts as complete.
- Ask the user questions in Russian (explicit user preference). Keep plugin-facing text in English per [`CLAUDE.md`](../../../CLAUDE.md). Use read transactions and the shared project/FQN helpers. The handoff itself is checkpoint-only; OpenCode 2.2.7 updates, installs and settings changes were excluded from this step.

## Working tree (uncommitted)

The index was empty. This session's implementation changes are below. In the listed paths, the Java source prefix is `mcp/bundles/com.ditrix.edt.mcp.server/src/com/ditrix/edt/mcp/server/`; the Java test prefix is `mcp/tests/com.ditrix.edt.mcp.server.tests/src/com/ditrix/edt/mcp/server/`.

| Files | Change |
| --- | --- |
| `tools/reference/ReferenceSourceScope.java` **(untracked)** | Parses strict nested scope, deduplicates entries, matches exact files and segment-safe prefixes. `resolveProjects()` uses `ProjectContext`, then `IFolder.findMember` under `src` to check selector existence and kind without visiting sources. An inaccessible project cannot prove absence; the future resource plan must report unavailable coverage, not widen the scope. |
| `tools/impl/FindReferencesTool.java`, `protocol/JsonSchemaBuilder.java`, `mcp/bundles/com.ditrix.edt.mcp.server/guides/find_references.md` | Declare the nested property and document selectors. Validate supplied scopes; valid scopes currently return `Scoped reference search is not available.` rather than entering the unscoped collector. Legacy execution is untouched. This is not a deployable scoped search. |
| `tools/IMcpTool.java`, `protocol/McpProtocolHandler.java`, `bridge/EdtMcpBridge.java` | Default no-op `validateRawArguments(Map<String, Object>)` runs before `extractToolParams`. It can return a `ToolResult` error without dispatch. The in-process bridge now preserves explicit JSON nulls on reserialization; the legacy string conversion still applies to tools that do not opt in. |
| Test files `tools/reference/ReferenceSourceScopeTest.java` **(untracked)**, `tools/impl/FindReferencesToolTest.java`, `protocol/McpProtocolHandlerTest.java` | Cover selector grammar, root-level exact files, overlap, unknown project/missing selector without traversal, schema shape, and rejection of `null`/stringified scopes before execution via both the dispatcher and bridge. |
| `openspec/changes/scope-find-references/{design.md,tasks.md}` | Approved plan revision; only 1.1 checked. `openspec/` as a whole was already untracked when this session began. |

Other **pre-existing untracked** paths: `.agents/`, `.claude/skills/openspec-*` entries shown by Git, `.codegraph/`, `.github/agents/`, `.github/workflows/copilot-setup-steps.yml`, and the pre-existing OpenSpec planning artifacts. Their provenance is not established; do not include them incidentally or discard them. No CI file was edited by this session. A fresh checkout of `origin/github-fork` will not contain the implementation, OpenSpec artifacts, or checkpoint; use the same working tree or explicitly transfer the changes.

## Evidence and remaining technical risks

- **Confirmed from code:** `McpProtocolHandler.extractToolParams` drops explicit nulls and converts all arguments to strings. `EdtMcpBridge.dispatchToolCall` also previously dropped nulls when serializing the request. Validating only in `FindReferencesTool.execute` could therefore turn an invalid scoped request into a broader legacy search. The raw hook fixes that for scope. For 3.1, inspect `JsonRpcRequest`/`GsonProvider`: raw `Map<String, Object>` still comes from Gson's object-number parsing. Check that fractional wire values near whole seconds cannot pass because of rounding; do not assume the hook preserves exact numeric lexemes.
- **Confirmed by `javap` on cached target SDK (`2026.1`, see `mcp/targets/default/default.target`):** `com._1c.g5.v8.bm.core.IBmCrossReference` exposes `getNamespace()` and `getObjectId()` separately from `getObject()`. `IBmEngine.getObjectResourceId(long)` is available. `IBmNamespace.asEngine()` and `IBmEngine.asNamespace()` are available. `IBmResourceInfo.getTopObjectFqn()` provides logical identity, not evidence of physical path. Do not call `ref.getObject()` or `getTopObjectByResourceId()` to decide admission; both return model objects. No tested API for `(namespace, resourceId)` to exact workspace file was found. Candidate approach: look for a resource descriptor/index mapping with independently verifiable physical paths, and instrument a read-only fixture probe. A logical BM URI, owner FQN or guessed path is insufficient; if no proof exists, use a genuinely resource-bounded semantic lookup or report incomplete coverage per design.
- **Confirmed from current collector code:** `MetadataReferenceService` serves both the ordinary search and strict delete checks; its BM branches still load sources before deriving their locations. `BslReferenceSearch` can dispatch an explicit source-URI list but may otherwise fall back to workspace scope. Neither collector was modified. These are starting points for tasks 2.1–2.2 after the plan is proved.
- **Last observed live:** `EDT-MCP-MKK.get_server_status()` reported plugin **2.26.1**, EDT **2026.2.2.21**, running windowed on port **8766**, with HTTP and IPC. Tool discovery showed no `sourceScope` or `timeoutSeconds`. `list_projects(format: "json")` listed `ExternalDataProcessors`, `mom`, `mom.OZON_HotFix`, `mom.yaxunit`; no `TestConfiguration` in that instance. Repository fixtures exist at `tests/TestConfiguration/.project` and `tests/tests/.project`. A separate ready EDT stand with the fixtures and a compatible plugin build is not confirmed. The live workspace was not altered or restarted; no fixture or `mom` search was run.

## Checks and processes

| Check | Result and scope |
| --- | --- |
| `openspec validate scope-find-references --strict`; `openspec instructions apply --change scope-find-references --json` | **Passed** after the design and task edits. CLI reported 1/12 complete, 1.2 next. |
| `git diff --check` | **Passed** for the tracked implementation diff. It does not check untracked files. |
| `bash source/compile.sh --java-home /Applications/1C/1CE/components/axiom-jdk-full-25.0.4+5-x86_64` (with `--output-dir` override) | **Failed before compilation.** Homebrew Maven 3.10.2 + Tycho 4.0.5; primary error `No implementation for MavenTargetLocationFactory was bound.` A Maven Central network warning also appeared. No compatible Maven 3.9 installation was found in the checked local paths, and the attempted download hosts were denied by the sandbox allowlist. Root cause not proved. Shell `sh_20da200eb001EmbQ6t281k2NVS`, completed exit 1; log under `~/.o3-cli/o3_plugin_agent/opencode-v2/data/opencode/shell/970180084bbc34c78b1343453369ef017059a80f/sh_20da200eb001EmbQ6t281k2NVS.out`. |
| Direct `javac --release 17` and `org.junit.runner.JUnitCore` | **Passed, 99 tests.** All plugin Java sources compiled using the EDT 2026.2.2+21 installed jars, cached p2 dependencies, and bundled SnakeYAML 2.2 first on the classpath. Java source copies with UTF-8 BOM removed, classpath, compiled classes and test launcher args are in ignored `mcp/bundles/com.ditrix.edt.mcp.server/target/scope-compile-6i0wz96c/`. The final run covered `FindReferencesToolTest`, `ReferenceSourceScopeTest`, `McpProtocolHandlerTest`, and `BridgeActivityTest`; no subsequent Java edits. If scratch survives, use the Axiom JVM's `bin/java @mcp/bundles/com.ditrix.edt.mcp.server/target/scope-compile-6i0wz96c/java-test-args` from the same checkout to rerun those compiled classes. This is not a Tycho/OSGi test run and does not prove live BM mapping. |
| Full plugin suite, live fixture acceptance, searches on `mom`, e2e/conformance, live redeploy | **Not run.** The registered EDT MCP was the old build. The legacy e2e file `tests/e2e/tools/test_find_references.py` is unchanged. |

No active subagents or background commands are known; the only background build finished. No PR or CI run was started or checked. The pre-existing EDT instance is external state, not an agent-owned process; recheck its version and workspace rather than assuming the observation is still current.

## Resume in this order

1. From the intended checkout, run `git status --short --branch`, `git rev-parse HEAD`, `git diff --cached --name-status`, then `openspec instructions apply --change scope-find-references --json`. Compare with this checkpoint. Read `CLAUDE.md`, the four OpenSpec artifacts linked above, and the applied `ReferenceSourceScope` / dispatch changes before editing. No background result is pending; inspect the saved build log if the error is still relevant.
2. Confirm the stand selection with the user if still unanswered. Use only an isolated EDT copy or authorized fixture bench; verify `TestConfiguration` and its test extension and obtain a known-compatible build toolchain there. Do not deploy into or restart the `mom` workspace to get fixture evidence.
3. Run a read-only probe in the fixture on control references from configuration, role, subsystem, and extension resources. For each, record the namespace, resource ID, independently known physical `src/` path, exact-file selector outcome, and whether any source was dereferenced before admission. Add a missing/unavailable-mapping case and verify fail-closed handling. Close 1.2 only when those observations are recorded on a real EDT platform, including exact matching and unavailable behavior; static SDK inspection or proxy tests alone do not qualify. Then build the shared resource plan in 1.3. If no safe true mapping or bounded alternative can be verified, keep the task open and surface the design blocker rather than guessing paths or loading excluded sources.
