"""Wire argument validation for project commit previews."""

from harness import assert_error, assert_no_diff, call, e2e_test


@e2e_test(tool="preview_project_commit", kind="read")
def test_preview_rejects_a_non_string_entry_id():
    error = assert_error(call("preview_project_commit", {
        "snapshotId": "b0bb4f47-9d5f-48ae-a8a5-0acfacba4fd1",
        "entryIds": [7],
        "message": "Review selected source",
    }))
    assert "entry UUIDs" in error
    assert_no_diff("Invalid previews must leave the repository alone")
