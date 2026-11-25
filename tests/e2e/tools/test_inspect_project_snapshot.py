"""Coverage for snapshot request validation and the read-only page contract."""

import os
import uuid

from harness import PROJECT, assert_error, assert_no_diff, assert_ok, call, e2e_test


@e2e_test(tool="inspect_project_snapshot", kind="read")
def test_resolution_rejects_a_foreign_id_and_can_read_its_own_snapshot():
    error = assert_error(call("inspect_project_snapshot", {"snapshotId": str(uuid.uuid4()), "view": "manifest"}))
    assert "Snapshot is unavailable" in error or "unavailable for this caller or project scope" in error
    if os.environ.get("EDT_MCP_SNAPSHOT_ACCEPTANCE") == "1":
        captured = call("capture_project_snapshot", {"projectName": PROJECT})
        assert_ok(captured)
        snapshot_id = captured.structured["snapshotId"]
        page = call("inspect_project_snapshot", {"snapshotId": snapshot_id, "view": "manifest", "limit": 1})
        assert_ok(page)
        assert page.structured["snapshotId"] == snapshot_id
        assert len(page.structured["entries"]) <= 1
        error = assert_error(call("inspect_project_snapshot", {
            "snapshotId": snapshot_id, "view": "manifest", "cursor": "wrong-cursor", "limit": 1,
        }))
        assert "cursor is invalid" in error
    assert_no_diff("inspect must leave the project's Git index and sources untouched")
