"""Wire argument validation for project commit confirmation."""

from harness import assert_error, assert_no_diff, call, e2e_test


@e2e_test(tool="confirm_project_commit", kind="read")
def test_confirm_rejects_an_invalid_plan_id():
    error = assert_error(call("confirm_project_commit", {"planId": "not-a-plan"}))
    assert "preview_project_commit" in error
    assert_no_diff("An invalid plan must leave the repository alone")
