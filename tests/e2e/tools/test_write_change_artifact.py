"""A build operation cannot manufacture owner consent."""

from harness import PROJECT, assert_error, assert_no_diff, call, e2e_test


@e2e_test(tool="write_change_artifact", kind="read")
def test_append_refuses_human_decision():
    assert_error(call("write_change_artifact", {"repoId":"r","projectName":PROJECT,
        "branch":"refs/heads/main","changeId":"change","kind":"designReview","operation":"append",
        "expectedRevision":"absent","base64":"QQ=="}))
    assert_no_diff("refused write is atomic")
