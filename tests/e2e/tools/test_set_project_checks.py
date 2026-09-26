"""
e2e tests for set_project_checks (kind: action).

WHAT THE TOOL DOES
------------------
set_project_checks toggles an EDT project's MASSIVE CHECK PROCESS (the per-project
preference `disableMassiveChecks`, node com.e1c.g5.v8.dt.check) through the
ICheckRepository service — the same path EDT's project property page uses, effective
immediately. disableMassiveChecks=true is meant to be set before update_database (to
skip the expensive checks/validations that make the update slow or blocked) and
false to restore normal checking after.

RESPONSE SHAPE
--------------
JSON tool (getResponseType() == JSON); payload in r.structured:
  success: {"success": true, "project", "disableMassiveChecks": <bool read-back>,
            "changed": <bool>, "message"}
  error:   {"success": false, "error": "..."}

CI STRATEGY -- NEGATIVES ONLY
-----------------------------
A real disable/enable flips the project's persisted check preference AND live
activates/deactivates check contexts through the workspace-metadata store — stateful,
not reset by the git-fixture cleanup this suite relies on. This file therefore
contains ONLY negative-path tests that are rejected BEFORE any
CheckProcessSupport/ICheckRepository write runs. The real happy path (a scratch repo)
is a LIVE, ATTENDED gate run by hand, never automated here.

Every test asserts assert_no_diff(): a rejected call must never touch the fixture.
"""

from harness import (
    call,
    assert_error,
    assert_error_quality,
    assert_no_diff,
    e2e_test,
    PROJECT,
)

NONEXISTENT_PROJECT = "NoSuchProject_spc_zzz"


@e2e_test(tool="set_project_checks", kind="action")
def test_missing_projectname_errors_with_hint():
    """No projectName -> the tool's own guard names it and steers to list_projects,
    before any project/check access."""
    r = call("set_project_checks", {"disableMassiveChecks": True})
    err = assert_error(r, "missing projectName")
    assert_error_quality(err, names=["projectName"], suggests=["list_projects"],
                         ctx="missing projectName must name it and steer to list_projects")
    assert_no_diff("a rejected call must not touch the fixture")


@e2e_test(tool="set_project_checks", kind="action")
def test_missing_disable_arg_errors():
    """A real project but no disableMassiveChecks -> the tool's own required-arg guard
    fires, naming the missing boolean."""
    r = call("set_project_checks", {"projectName": PROJECT})
    err = assert_error(r, "missing disableMassiveChecks")
    assert_error_quality(err, names=["disableMassiveChecks"],
                         ctx="missing disableMassiveChecks must name the parameter")
    assert_no_diff("a rejected call must not touch the fixture")


@e2e_test(tool="set_project_checks", kind="action")
def test_invalid_disable_arg_errors():
    """A non-boolean disableMassiveChecks value is rejected by the tool's pure parser —
    reachable even against a made-up project since it fires BEFORE any project/check
    resolution, so no state is written."""
    r = call("set_project_checks", {"projectName": NONEXISTENT_PROJECT, "disableMassiveChecks": "banana"})
    err = assert_error(r, "invalid disableMassiveChecks")
    assert_error_quality(err, names=["banana", "true", "false"],
                         ctx="invalid boolean must name the bad value and the allowed ones")
    assert_no_diff("a rejected call must not touch the fixture")


@e2e_test(tool="set_project_checks", kind="action")
def test_nonexistent_project_errors_with_hint():
    """A syntactically valid but non-existent project cannot resolve -> 'not found'
    error naming the bad value and steering to list_projects, BEFORE any check write."""
    r = call("set_project_checks", {"projectName": NONEXISTENT_PROJECT, "disableMassiveChecks": True})
    err = assert_error(r, "nonexistent project")
    assert_error_quality(err, names=[NONEXISTENT_PROJECT], suggests=["list_projects"],
                         ctx="nonexistent project is named in the not-found error")
    assert_no_diff("a rejected call must not touch the fixture")
