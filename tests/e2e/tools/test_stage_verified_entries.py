"""An invalid selection must never update the index."""

import uuid
from harness import assert_error, assert_no_diff, call, e2e_test


@e2e_test(tool="stage_verified_entries", kind="read")
def test_stage_rejects_unknown_source():
    assert_error(call("stage_verified_entries", {
        "repoId": "missing", "snapshotId": str(uuid.uuid4()), "entryIds": [str(uuid.uuid4())],
    }))
    assert_no_diff("invalid stage must leave the index intact")
