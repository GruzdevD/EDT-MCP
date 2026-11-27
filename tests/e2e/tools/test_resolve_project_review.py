"""History is available only for explicitly authorized project scopes."""

from harness import PROJECT, assert_error, assert_no_diff, call, e2e_test


@e2e_test(tool="resolve_project_review", kind="read")
def test_unavailable_repository_returns_no_history():
    assert_error(call("resolve_project_review", {
        "repoId": "unknown-repo", "projectName": PROJECT, "branch": "refs/heads/main", "changeId": "review",
    }))
    assert_no_diff("history resolution is read-only")
