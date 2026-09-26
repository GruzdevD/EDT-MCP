"""
e2e tests for create_project_application (kind: action).

WHAT THE TOOL DOES
------------------
create_project_application mints a NEW application for an EDT project, bound to
a git BRANCH context and pointing at an EXISTING infobase (named from
list_infobases), WITHOUT creating or registering a new database. It clones the
named base's connection string into a fresh InfobaseReference (new id + uuid +
name) and associates it with the branch context through
IInfobaseAssociationManager as already-synchronized; the read-back then re-checks
get_applications for the new application (issue #412 honesty:
BOUND / NOT_BOUND / UNVERIFIED).

RESPONSE SHAPE
--------------
JSON tool (getResponseType() == JSON); payload in r.structured:
  success: {"success": true, "project", "branch", "infobaseName",
            "applicationName", "binding": "BOUND|NOT_BOUND|UNVERIFIED",
            ["applicationId"], ["applications"], ["bound"]}
  error:   {"success": false, "error": "..."}

CI STRATEGY -- NEGATIVES ONLY
-----------------------------
Like set_branch_infobase / list_git_branches, the CI fixture project (PROJECT,
"TestConfiguration") lives INSIDE the EDT-MCP plugin's OWN git working tree. A happy
path here would register a clone into the EDT infobase panel and record an
association in the workspace-metadata store — both stateful and neither reset by the
git-fixture cleanup this suite relies on. This file therefore contains ONLY
negative-path tests that are rejected BEFORE any IInfobaseManager /
IInfobaseAssociationManager mutation runs. The real happy path (a scratch repo + a
real registered base) is a LIVE, ATTENDED gate run by hand, never automated here.

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

NONEXISTENT_PROJECT = "NoSuchProject_cpa_zzz"
NONEXISTENT_INFOBASE = "no_such_infobase_cpa_zzz"


@e2e_test(tool="create_project_application", kind="action")
def test_missing_projectname_errors_with_hint():
    """No projectName -> the shared required-arg guard names it and steers to
    list_projects, before any resolution/mutation."""
    r = call("create_project_application", {"branch": "feature/x", "infobaseName": NONEXISTENT_INFOBASE})
    err = assert_error(r, "missing projectName")
    assert_error_quality(err, names=["projectName"], suggests=["list_projects"],
                         ctx="missing projectName must name it and steer to list_projects")
    assert_no_diff("a rejected call must not touch the fixture")


@e2e_test(tool="create_project_application", kind="action")
def test_missing_branch_errors_clearly():
    """A real project + infobase but no branch -> the required-arg guard names it."""
    r = call("create_project_application", {
        "projectName": PROJECT, "infobaseName": NONEXISTENT_INFOBASE,
    })
    err = assert_error(r, "missing branch")
    assert_error_quality(err, names=["branch"], ctx="missing branch must name the parameter")
    assert_no_diff("a rejected call must not touch the fixture")


@e2e_test(tool="create_project_application", kind="action")
def test_missing_infobasename_errors_with_hint():
    """A real project + branch but no infobaseName -> the required-arg guard names it
    and steers to list_infobases (the discovery tool for the base to bind)."""
    r = call("create_project_application", {"projectName": PROJECT, "branch": "feature/x"})
    err = assert_error(r, "missing infobaseName")
    assert_error_quality(err, names=["infobaseName"], suggests=["list_infobases"],
                         ctx="missing infobaseName must name it and steer to list_infobases")
    assert_no_diff("a rejected call must not touch the fixture")


@e2e_test(tool="create_project_application", kind="action")
def test_nonexistent_project_errors_with_hint():
    """A syntactically valid but non-existent project cannot resolve a repository ->
    'not found' error naming the bad value and steering to list_projects, BEFORE any
    infobase lookup or association."""
    r = call("create_project_application", {
        "projectName": NONEXISTENT_PROJECT, "branch": "feature/x",
        "infobaseName": NONEXISTENT_INFOBASE,
    })
    err = assert_error(r, "nonexistent project")
    assert_error_quality(err, names=[NONEXISTENT_PROJECT], suggests=["list_projects"],
                         ctx="nonexistent project is named in the not-found error")
    assert_no_diff("a rejected call must not touch the fixture")


@e2e_test(tool="create_project_application", kind="action")
def test_nonexistent_infobase_errors_with_hint():
    """A real project + branch but an UNKNOWN infobase name -> the base lookup (by
    name, from list_infobases) fails, naming the bad value and steering to
    list_infobases — and this happens BEFORE any association mutation runs, so no
    state is written."""
    r = call("create_project_application", {
        "projectName": PROJECT, "branch": "feature/x", "infobaseName": NONEXISTENT_INFOBASE,
    })
    err = assert_error(r, "nonexistent infobase")
    assert_error_quality(err, names=[NONEXISTENT_INFOBASE], suggests=["list_infobases"],
                         ctx="nonexistent infobase must be named and steer to list_infobases")
    assert_no_diff("a rejected call must not touch the fixture")
