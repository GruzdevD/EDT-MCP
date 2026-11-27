"""Artifact paths are selected by type, never supplied by the client."""

from harness import PROJECT, assert_error, assert_no_diff, call, e2e_test


@e2e_test(tool="read_change_artifact", kind="read")
def test_external_artifact_path_is_refused():
    error = assert_error(call("read_change_artifact", {"repoId":"r","projectName":PROJECT,
        "branch":"refs/heads/main","changeId":"change","view":"read","kind":"delta","itemId":"../outside"}))
    assert "outside" not in error
    assert_no_diff("artifact inspection is read-only")
