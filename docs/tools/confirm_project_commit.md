# confirm_project_commit

Confirm the `planId` from `preview_project_commit`. The server checks the write grant, consent, and the reviewed state, then returns the commit SHA and tree ID. Only the selected index entries are updated. A deleted path created after the final check stays in the working tree as a new change.

If the response is lost, repeat the same `planId`. The retained result identifies the same commit. A `recovery_required` response identifies the SHA for administrator inspection when the server cannot safely finish the index update.

See the [bundled guide](../../mcp/bundles/com.ditrix.edt.mcp.server/guides/confirm_project_commit.md) for the request and recovery behavior.
