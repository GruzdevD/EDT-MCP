"""Versioned review requires an authenticated scope and an existing version."""

import uuid

from harness import PROJECT, assert_error, assert_no_diff, call, e2e_test


@e2e_test(tool="inspect_project_version", kind="read")
def test_unknown_snapshot_cannot_return_source():
    error = assert_error(call("inspect_project_version", {
        "repoId": "review", "projectName": PROJECT, "snapshotId": str(uuid.uuid4()),
        "view": "manifest", "side": "disk",
    }))
    assert "unavailable" in error.lower()
    assert_no_diff("version inspection must preserve sources and index")
