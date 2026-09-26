"""
e2e tests for delete_project_application (kind: action).

WHAT THE TOOL DOES
------------------
delete_project_application deletes an EDT project application — a project-to-infobase
binding — via IApplicationManager.delete(app, unsynchronize=true), the same EDT API the
GUI uses. It removes the application from get_applications for the project WITHOUT
removing the infobase itself (the base stays in list_infobases and on disk). This is the
inverse of create_project_application. Two-phase: without confirm it previews; confirm=true
performs the delete and reports a read-back verdict (removed + remainingApplications).

RESPONSE SHAPE
--------------
JSON tool (getResponseType() == JSON); payload in r.structured:
  preview: {"success": true, "action": "preview", "confirmationRequired": true,
            "project", "applicationId", "applicationName", "message"}
  deleted: {"success": true, "action": "deleted", "project", "applicationId",
            "applicationName", "removed": <bool>, ["remainingApplications"], "message"}
  error:   {"success": false, "error": "..."}

CI STRATEGY -- NEGATIVES ONLY
-----------------------------
A real delete mutates the workspace-metadata store / application registry in the CI
fixture project (PROJECT, "TestConfiguration") — stateful and NOT reset by the git-fixture
cleanup this suite relies on (like create_project_application / set_branch_infobase).
This file therefore contains ONLY negative-path tests that are rejected BEFORE any
IApplicationManager.delete mutation runs. The real happy path (a scratch repo + a real
registered application) is a LIVE, ATTENDED gate run by hand, never automated here.

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

NONEXISTENT_PROJECT = "NoSuchProject_dpa_zzz"
NONEXISTENT_APP = "NoSuchApplication_dpa_zzz"


@e2e_test(tool="delete_project_application", kind="action")
def test_missing_projectname_errors_with_hint():
    """No projectName -> the shared required-arg guard names it and steers to
    list_projects, before any resolution/mutation."""
    r = call("delete_project_application", {"applicationId": NONEXISTENT_APP})
    err = assert_error(r, "missing projectName")
    assert_error_quality(err, names=["projectName"], suggests=["list_projects"],
                         ctx="missing projectName must name it and steer to list_projects")
    assert_no_diff("a rejected call must not touch the fixture")


@e2e_test(tool="delete_project_application", kind="action")
def test_missing_applicationid_errors_with_hint():
    """A real project but no applicationId -> the required-arg guard names it and steers
    to get_applications (the discovery tool for valid application IDs)."""
    r = call("delete_project_application", {"projectName": PROJECT})
    err = assert_error(r, "missing applicationId")
    assert_error_quality(err, names=["applicationId"], suggests=["get_applications"],
                         ctx="missing applicationId must name it and steer to get_applications")
    assert_no_diff("a rejected call must not touch the fixture")


@e2e_test(tool="delete_project_application", kind="action")
def test_nonexistent_project_errors_with_hint():
    """A syntactically valid but non-existent project cannot resolve -> 'not found' error
    naming the bad value and steering to list_projects, BEFORE any application lookup."""
    r = call("delete_project_application", {
        "projectName": NONEXISTENT_PROJECT, "applicationId": NONEXISTENT_APP,
    })
    err = assert_error(r, "nonexistent project")
    assert_error_quality(err, names=[NONEXISTENT_PROJECT], suggests=["list_projects"],
                         ctx="nonexistent project is named in the not-found error")
    assert_no_diff("a rejected call must not touch the fixture")


@e2e_test(tool="delete_project_application", kind="action")
def test_nonexistent_application_errors_with_hint():
    """A real project but an UNKNOWN application id -> the application lookup fails, naming
    the bad value and steering to get_applications — this happens BEFORE any mutation, so
    no state is written (and the missing app means nothing could be deleted anyway)."""
    r = call("delete_project_application", {"projectName": PROJECT, "applicationId": NONEXISTENT_APP})
    err = assert_error(r, "nonexistent application")
    assert_error_quality(err, names=[NONEXISTENT_APP], suggests=["get_applications"],
                         ctx="nonexistent application must be named and steer to get_applications")
    assert_no_diff("a rejected call must not touch the fixture")
