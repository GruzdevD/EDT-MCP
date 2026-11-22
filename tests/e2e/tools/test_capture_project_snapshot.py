"""Coverage for snapshot admission on default and opted-in EDT stands."""

import os

from harness import PROJECT, assert_error, assert_no_diff, assert_ok, call, e2e_test


@e2e_test(tool="capture_project_snapshot", kind="read")
def test_capture_requires_the_dedicated_authenticated_stand():
    result = call("capture_project_snapshot", {"projectName": PROJECT})
    if os.environ.get("EDT_MCP_SNAPSHOT_ACCEPTANCE") == "1":
        assert_ok(result)
        value = result.structured or {}
        assert value.get("snapshotId") and value.get("expiresAt")
        assert [project["projectName"] for project in value["projects"]] == [PROJECT]
        assert value["projects"][0]["sourceFingerprint"]
        assert value["modelStatus"] in {"verified", "diverged", "unknown"}
    else:
        error = assert_error(result)
        assert "unavailable for this caller or project scope" in error
    assert_no_diff("capture must leave the project's Git index and sources untouched")
