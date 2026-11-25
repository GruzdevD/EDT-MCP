# preview_project_commit

Preview a commit from changed disk entries in one retained project snapshot. Get the entry IDs with `inspect_project_snapshot` using the snapshot without `compareTo`. Pass the `snapshotId`, explicit `entryIds`, and final commit `message`. Select both IDs for an identified rename.

The preview reports the captured `modelStatus`, branch, parent SHA, tree ID, message, and selected versions. A stable capture with `modelStatus: "unknown"` is eligible. The status indicates that EDT could not attest that the model matches the disk. A `diverged` capture is rejected.

Each selected project must permit the exact local branch in its server-side `commitBranches` list. Select files from one ordinary local Windows work tree with an attached HEAD. Resolve index conflicts and align partially staged selections before retrying. Repositories with required commit signing, commit hooks, unsupported index layouts, or unconfirmed disk normalization are refused. Unselected staged changes do not enter the planned tree.

Filter drivers declared in the system or user Git configuration, including Git LFS, can make the disk comparison unconfirmed even on a repository without its own filter rules.

```json
{"snapshotId":"b0bb4f47-9d5f-48ae-a8a5-0acfacba4fd1","entryIds":["1dc01644-aca0-4fcf-bdff-d878f1a4e000"],"message":"Update the calculation module"}
```

The authenticated Windows EDT server reads the project grant from `edt.mcp.snapshots.policy`. For example, this grant adds preview access on `refs/heads/review` and gives confirmation access on that branch through `commit: true`.

```json
{"TestConfiguration":{"location":"C:\\work\\TestConfiguration","extensions":["bsl","mdo"],"commitBranches":["refs/heads/review"],"commit":true}}
```

Review the selected `entries`, `parentSha`, `treeId`, and `message`, then pass `planId` to `confirm_project_commit`. An expired snapshot or a changed Git state calls for a new capture and preview.
