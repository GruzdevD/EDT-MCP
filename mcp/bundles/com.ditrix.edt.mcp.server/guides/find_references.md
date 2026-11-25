Finds semantic references to one metadata object in BSL modules and metadata resources, including forms, roles, subsystems, produced types, predefined items and fields. Select a source block when the configuration is too large to search in one call.

## When to use
- Before renaming or deleting an object, to assess the blast radius.
- To understand where a catalog/document/register/common module is consumed.
- To find an identifier regardless of its ru/en spelling - unlike `search_in_code`, this is model-aware, not a literal text search.

## Parameter details
- `projectName` (required) - EDT project that owns the target. It does not restrict the sources selected by `sourceScope`.
- `objectFqn` (required) - fully qualified name `Type.Name` of the object to find references for. The `Name` part is the object's programmatic name (never its synonym). Only the leading TYPE token is bilingual: `Catalog.Products` and `Справочник.Products` resolve to the same object (the Russian type spelling is normalized internally). Examples: `Catalog.Products`, `Document.SalesOrder`, `CommonModule.Common`, `InformationRegister.Prices`.
- `sourceScope` - optional object with a nonempty `projectNames` array of exact EDT project names and an optional nonempty `paths` array. Omit `paths` for entire `src/` trees, including files directly under `src/`. Each path is relative to `src/` and uses `/`. A trailing slash selects a directory subtree (for example `CommonModules/`); otherwise it selects one exact file. The selectors form a union without duplicates, and each must exist in at least one of the selected projects. Absolute paths, `.` or `..` segments, unknown properties, and `null` are errors; invalid scopes never fall back to a broader search.
- `timeoutSeconds` is the server's total search budget. Pass a JSON integer from 1 through 3600. The default is 45, including time in the job queue and waiting for EDT's model and reference index.
- `waitSeconds` is how long the current call can wait for the job. It defaults to 5 and accepts 0 through 45. Use 0 to return a job ID immediately. Poll that ID with `get_job_status`. A different poll wait leaves the search deadline intact.
- `limit` caps unique structured references across all categories, defaults to 100, and is clamped to 500. The legacy Markdown form keeps its `limit*10` collection hint.

## Structured results

Supplying `sourceScope`, `timeoutSeconds`, or `waitSeconds` returns a job envelope. Check `status` and continue polling the same `jobId` while it is `running`. When `status` is `done`, read `result`.

Project names and path existence are checked by the background job. If an immediate call returns a job ID for an unknown project or path, `get_job_status` reports `status: "failed"` and an actionable `error`. Malformed parameter values are rejected at submission.

- `target` gives the owner project and normalized FQN. An empty `requestedScope` selects the default search area. A supplied scope searches only its selected source projects and files.
- `effectiveCheckedScope` lists fully examined resources and `examinedResourceCount` counts all of them. When `checkedScopeTruncated` is true, the list shows up to 500 entries. Check `unavailable` and `unavailableCount` for gaps.
- Each entry in `references` has an occurrence `id`, source project, path relative to `src/`, and type. A metadata entry can give the exact owner, its containment fragment and property. BSL entries provide the AST fragment and any available offset, line and column. Line and column are one-based. Multiple occurrences on the same line can have different IDs.
- `complete` is true for a completed search. `stopReason` is one of `completed`, `timeout`, `limit`, `resource_unavailable`, or `index_unavailable`. Hitting the cap exactly can still complete. `limit` means an extra distinct occurrence was encountered. A timeout preserves the rows published within the budget, even when the model or index call takes longer to unwind.
- `stateId` is `unavailable` on the current EDT API. Keep the model, source files and index unchanged between block searches. Merge only completed results for the same target and deduplicate by occurrence `id`. Retry an incomplete block or record the gap before drawing conclusions from a zero count.

## Result categories
References are grouped by where they were found, e.g.:
- `BSL Modules` - code references, each with module path and line number.
- Metadata features - attributes, tabular sections, forms, commands, roles, subsystems and other objects that type-reference or contain the object.
- `Predefined items` - usages of the object's predefined values.
- `Field references` - field-level usages.

## Examples

Legacy Markdown (the Russian type token `Справочник` also resolves the same catalog).

```json
{"projectName":"TestConfiguration","objectFqn":"Catalog.Catalog"}
```

Structured search over the default area with a longer budget.

```json
{"projectName":"TestConfiguration","objectFqn":"CommonModule.Calc","timeoutSeconds":300}
```

Search only one extension source for a target owned by the base.

```json
{"projectName":"TestConfiguration","objectFqn":"CommonModule.Calc","sourceScope":{"projectNames":["TestConfiguration.tests"],"paths":["CommonModules/tests_SampleTests/Module.bsl"]},"waitSeconds":0}
```

Poll the search with `get_job_status`.

```json
{"jobId":"<jobId>","waitSeconds":5}
```

## Notes & gotchas
- An unknown or malformed `objectFqn` (no `Type.Name`, or a type/name that does not exist) returns an error - check the type token and the programmatic name.
- Resolution is by the object's `Name`, NOT by its synonym; passing a synonym will not match.
- For a literal text search (comments, messages, raw strings) use `search_in_code` instead; for call graphs of a specific method use `get_method_call_hierarchy`.
