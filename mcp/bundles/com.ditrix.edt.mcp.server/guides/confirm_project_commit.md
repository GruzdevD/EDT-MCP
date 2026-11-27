# confirm_project_commit

Submit the `planId` returned by `preview_project_commit` after reviewing the selected paths, content, parent, and message. The authenticated EDT server checks the current branch grant, `commit: true`, and the consent setting for this tool. The server uses its Git identity for the commit.

```json
{"planId":"d4b9ae32-be73-407a-84e4-0627efd78cf3"}
```

`status: "committed"` returns the full `commitSha`, `branch`, `parentSha`, and `treeId`. Unselected staged and unstaged changes are preserved. If a deleted path has reappeared by the final check, confirmation refuses the plan. A file created after that check remains a new working-tree change.

A stale plan requires a new snapshot and preview. If the response was lost, send the same `planId`. While the journal retains the result, a repeat returns the original SHA. `recovery_required` means the administrator may need to inspect the reported SHA, branch, and index. Retrying that ID can finish a provable incomplete index update. The general `git` tool is optional.

For opt-in build previews, a completed receipt is discoverable by repoId, projectName, branch and changeId via inspect_change_evidence (view=receipts) for seven days. Ordinary commit results keep their ten-minute retention. A pending recovery must be resolved before another commit to the same repository. An unconfirmed preview expires after five minutes and requires fresh review and consent.
