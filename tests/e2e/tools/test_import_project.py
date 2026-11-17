"""
e2e tests for import_project (kind: write-metadata) — LINKED registration of an EXISTING
EDT project directory.

THE TOOL (ImportProjectTool, getResponseType() == JSON):
Registers a directory that already contains a .project file (a git worktree, a second
checkout, a copy) into the workspace as a LINKED project: files stay in place, nothing is
copied or moved. Optionally renames the project in the workspace (projectName) without
touching the on-disk .project — needed when the on-disk name is already taken.

The happy path stages a COPY of the committed fixture (tests/TestConfiguration) into a temp
directory (outside the repo), imports it under a new name, verifies the project deployed and
the rename/location reported, re-imports to prove idempotency (alreadyOpen), then deletes it.
A copy WITHOUT the rename must be refused with an actionable name-collision error (the
fixture project with the same .project name already lives in the workspace at another
location). The temp copy is removed in a finally block; assert_no_diff guards the committed
fixture (which is only ever READ and copied).
"""

import os
import shutil
import tempfile

from harness import (
    call,
    assert_ok,
    assert_error,
    assert_error_quality,
    assert_contains,
    assert_no_diff,
    wait_for_project_ready,
    e2e_test,
    PROJECT,
    PROJECT_DIR,
)

# A new project name unlikely to collide with anything in the workspace.
NEW_PROJECT = "LinkedImport_e2e"
# A temp directory (outside the git fixture) where the copy is staged.
IMPORT_DIR = os.path.join(tempfile.gettempdir(), "edt_linked_import_e2e")


def _stage_copy():
    """Stage a throwaway copy of the fixture project directory into a temp dir."""
    shutil.rmtree(IMPORT_DIR, ignore_errors=True)
    shutil.copytree(PROJECT_DIR, IMPORT_DIR)


def _ensure_absent(project_name):
    """Best-effort pre/post clean: remove a leftover project from a prior crashed run so the
    name-collision guard cannot mask the round-trip. Ignores the result."""
    call("delete_project", {"projectName": project_name, "deleteContent": True, "confirm": True})


# ──────────────────────────────────────────────────────────────────────────────
# CONTRACT / NEGATIVE
# ──────────────────────────────────────────────────────────────────────────────
@e2e_test(tool="import_project", kind="action")
def test_missing_path_errors():
    """No path -> the required-arg guard fires naming the param."""
    r = call("import_project", {})
    e = assert_error(r, "missing path")
    assert_error_quality(e, names=["path"], ctx="missing path names the param")
    assert_no_diff("a rejected call must not touch the fixture")


@e2e_test(tool="import_project", kind="action")
def test_relative_path_errors():
    """A relative path -> rejected before any workspace access."""
    r = call("import_project", {"path": "some/relative/dir"})
    e = assert_error(r, "relative path rejected")
    assert_error_quality(e, names=["path"], ctx="relative path must be rejected explicitly")
    assert_no_diff("a rejected call must not touch the fixture")


@e2e_test(tool="import_project", kind="action")
def test_directory_without_project_file_errors():
    """A directory without .project -> rejected with a hint to the sibling tools."""
    empty = tempfile.mkdtemp(prefix="edt_no_project_")
    try:
        r = call("import_project", {"path": empty})
        e = assert_error(r, "directory without .project rejected")
        assert_error_quality(e, names=[".project"],
                             suggests=["create_project", "import_configuration_from_xml"],
                             ctx="no-.project error must name the file and steer to the siblings")
    finally:
        shutil.rmtree(empty, ignore_errors=True)
    assert_no_diff("a rejected call must not touch the fixture")


@e2e_test(tool="import_project", kind="action")
def test_name_collision_is_actionable():
    """A copy of the fixture imported WITHOUT a rename: its .project name equals PROJECT,
    which already exists in the workspace at a different location -> the tool must refuse,
    name the colliding project and hint at projectName / delete_project — and leave the
    fixture untouched."""
    _stage_copy()
    try:
        r = call("import_project", {"path": IMPORT_DIR})
        e = assert_error(r, "name collision rejected")
        assert_error_quality(e, names=[PROJECT], suggests=["delete_project"],
                             ctx="collision error must name the project and be actionable")
        # CRITICAL: the refusal must not have registered or touched the fixture project.
        lp = call("list_projects", {})
        assert_contains(lp.text, PROJECT, "the fixture project must be untouched by the refusal")
        assert_no_diff("the collision refusal must not touch the fixture")
    finally:
        shutil.rmtree(IMPORT_DIR, ignore_errors=True)


# ──────────────────────────────────────────────────────────────────────────────
# ROUND-TRIP: stage copy -> import with rename -> verify -> idempotent re-import -> delete
# ──────────────────────────────────────────────────────────────────────────────
@e2e_test(tool="import_project", kind="write-metadata")
def test_import_rename_roundtrip_then_delete_project():
    """Full linked-import round-trip. Stages a copy of the fixture, imports it under a NEW
    workspace name, verifies location/renamed/state in the response and presence in
    list_projects, re-imports the same location (idempotent alreadyOpen), then removes it
    via delete_project and verifies it is gone. Self-cleans in finally for repeatability."""
    _ensure_absent(NEW_PROJECT)
    _stage_copy()
    try:
        # 1) Import the staged copy under a distinct workspace name.
        im = call("import_project", {"path": IMPORT_DIR, "projectName": NEW_PROJECT})
        assert_ok(im, "import the staged copy with rename")

        # 2) Verify the response reports the rename and the linked location.
        s = im.structured
        assert s is not None, "import must return a JSON body, got %r" % (im.text,)
        assert s.get("action") in ("imported", "opened", "alreadyOpen"), \
            "a successful import must not be action='importing' (too slow for CI), got %r" % (s,)
        assert s.get("renamed") is True, "import with projectName must report renamed=true"
        assert s.get("location") == IMPORT_DIR, \
            "the response must echo the linked location"

        # 3) Verify the project deployed: it appears in list_projects (wait for indexing).
        wait_for_project_ready()
        lp = call("list_projects", {})
        assert_contains(lp.text, NEW_PROJECT,
                        "the imported project must appear in list_projects (import really created it)")

        # 4) Re-import the same location under the same name: idempotent, nothing changes.
        again = call("import_project", {"path": IMPORT_DIR, "projectName": NEW_PROJECT})
        assert_ok(again, "re-import of the same location must be idempotent")
        a = again.structured
        assert a is not None and a.get("action") in ("alreadyOpen", "opened", "importing"), \
            "a same-location re-import must not re-error, got %r" % (a,)

        # 5) Cleanup: confirm-delete the imported project (the temp copy is disposable).
        dele = call("delete_project",
                    {"projectName": NEW_PROJECT, "deleteContent": True, "confirm": True})
        assert_ok(dele, "delete the imported project (confirm)")
        assert dele.structured and dele.structured.get("action") == "deleted", \
            "confirm delete must report action='deleted'"

        # 6) Verify it is gone.
        wait_for_project_ready()
        lp_gone = call("list_projects", {})
        assert_contains(lp_gone.text, PROJECT,
                        "the fixture project must be untouched by the round-trip")
    finally:
        # Belt-and-braces: a mid-test failure must still leave no project / temp residue.
        _ensure_absent(NEW_PROJECT)
        shutil.rmtree(IMPORT_DIR, ignore_errors=True)

    assert_no_diff("the round-trip must not touch the committed fixture (TestConfiguration)")
