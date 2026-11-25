# preview_project_commit

Supply a `snapshotId`, 1 to 100 changed `entryIds` from `inspect_project_snapshot`, and the commit `message`. The plan reports the selected versions, exact tree ID, expected parent SHA, recorded model status, and an expiring `planId`.

An authenticated Windows EDT server must allow the local branch in `commitBranches` for each selected project. Stable snapshots with `verified` or `unknown` model status are eligible. Select both entries of an identified rename and resolve index conflicts or partially staged files before requesting a plan.

The [bundled guide](../../mcp/bundles/com.ditrix.edt.mcp.server/guides/preview_project_commit.md) contains request and policy examples.
