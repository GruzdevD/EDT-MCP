"""
e2e tests for remove_standalone_server (kind: action).

WHAT THE TOOL DOES
------------------
remove_standalone_server removes the WST STANDALONE-server wiring that makes an EDT
project treat an infobase as an autonomous server, so a NORMAL infobase application can
be created for it. Two-phase (like delete_infobase / delete_project_application): without
confirm it previews the matched servers; confirm=true deletes them. The served DATABASE
is never touched — only the server registration and its config entry.

RESPONSE SHAPE
--------------
JSON tool (getResponseType() == JSON); payload in r.structured:
  preview: {"success": true, "project", "infobaseName", "confirmationRequired": true,
            "matchedServers": [...], "allServers": [...], "message"}
  removed: {"success": true, "project", "infobaseName", "removedServers": [...],
            "remainingServers": [...], "message"}
  error:   {"success": false, "error": "..."}

CI STRATEGY -- NEGATIVES ONLY
-----------------------------
A real removal deletes standalone-server registrations + the infobases.yaml entry in the
CI fixture project — stateful and NOT reset by the git-fixture cleanup this suite relies
on. This file therefore contains ONLY negative-path tests that are rejected BEFORE any
mutation runs. The real happy path (a scratch repo with an autonomous-server base) is a
LIVE, ATTENDED gate run by hand, never automated here.

Every test asserts assert_no_diff(): a rejected call must never touch the fixture.
"""

from harness import (
    call,
    assert_error,
    assert_error_quality,
    assert_no_diff,
    assert_ok,
    e2e_test,
    PROJECT,
)

NONEXISTENT_PROJECT = "NoSuchProject_rss_zzz"
NONEXISTENT_BASE = "NoSuchInfobase_rss_zzz"


@e2e_test(tool="remove_standalone_server", kind="action")
def test_missing_projectname_errors_with_hint():
    """No projectName -> the shared required-arg guard names it and steers to
    list_projects, before any resolution/mutation."""
    r = call("remove_standalone_server", {"infobaseName": NONEXISTENT_BASE})
    err = assert_error(r, "missing projectName")
    assert_error_quality(err, names=["projectName"], suggests=["list_projects"],
                         ctx="missing projectName must name it and steer to list_projects")
    assert_no_diff("a rejected call must not touch the fixture")


@e2e_test(tool="remove_standalone_server", kind="action")
def test_missing_infobasename_errors_with_hint():
    """A real project but no infobaseName -> the required-arg guard names it and steers
    to list_infobases (the discovery tool for registered bases)."""
    r = call("remove_standalone_server", {"projectName": PROJECT})
    err = assert_error(r, "missing infobaseName")
    assert_error_quality(err, names=["infobaseName"], suggests=["list_infobases"],
                         ctx="missing infobaseName must name it and steer to list_infobases")
    assert_no_diff("a rejected call must not touch the fixture")


@e2e_test(tool="remove_standalone_server", kind="action")
def test_nonexistent_project_errors_with_hint():
    """A syntactically valid but non-existent project cannot resolve -> 'not found' error
    naming the bad value and steering to list_projects, BEFORE any server lookup."""
    r = call("remove_standalone_server", {
        "projectName": NONEXISTENT_PROJECT, "infobaseName": NONEXISTENT_BASE,
    })
    err = assert_error(r, "nonexistent project")
    assert_error_quality(err, names=[NONEXISTENT_PROJECT], suggests=["list_projects"],
                         ctx="nonexistent project is named in the not-found error")
    assert_no_diff("a rejected call must not touch the fixture")


@e2e_test(tool="remove_standalone_server", kind="action")
def test_preview_with_no_match_is_non_destructive():
    """A REAL project + a base name that matches nothing should PREVIEW an empty removal
    (success + confirmationRequired, matchedServers=[]) rather than fail or write
    anything — the honest 'nothing wired' verdict with allServers as evidence. A preview
    never deletes, so this is safe to exercise against the shared fixture."""
    r = call("remove_standalone_server", {
        "projectName": PROJECT, "infobaseName": NONEXISTENT_BASE,
    })
    assert_ok(r, "a no-match preview must succeed, not error")
    structured = r.structured or {}
    assert structured.get("confirmationRequired") is True, \
        "preview must set confirmationRequired=true [%s]" % structured
    assert structured.get("matchedServers") == [], \
        "no-match preview must have empty matchedServers [%s]" % structured
    assert "allServers" in structured, "preview must include allServers evidence"
    assert_no_diff("a preview must not touch the fixture")
