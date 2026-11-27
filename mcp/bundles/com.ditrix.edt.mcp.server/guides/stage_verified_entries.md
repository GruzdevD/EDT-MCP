## Stage verified source entries

Enable the exact full branch in both branches and stageBranches under the project's review policy. The operation checks branch, parent, captured index and selected disk bytes. The server holds selected files and the Git index through publication. It preserves unrelated staged content and refuses partial staging, unresolved conflicts and restored deletions.

    {"repoId":"app","snapshotId":"<capture ID>","entryIds":["<changed BSL entry>","<changed metadata entry>"]}

A successful response identifies the parent and matching Git blob IDs for the selected index and disk versions. Both are absent for a deletion. For the next preview, capture again with capture_project_snapshot, read the new entry IDs using inspect_project_snapshot and pass those IDs to preview_project_commit.

Staging rechecks Git normalization settings and applicable attribute files before publishing the index.
