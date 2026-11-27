"""Evidence handles require authorization for their repository and change."""

from harness import PROJECT, assert_error, assert_no_diff, call, e2e_test


@e2e_test(tool="inspect_change_evidence", kind="read")
def test_unavailable_evidence_is_not_disclosed():
    assert_error(call("inspect_change_evidence", {
        "repoId": "unavailable", "projectName": PROJECT, "branch": "refs/heads/missing",
        "changeId": "missing-change", "cycle": "one", "view": "list",
    }))
    assert_no_diff("reading evidence must preserve the index and disk")
