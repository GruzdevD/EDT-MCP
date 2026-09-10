"""
e2e tests for list_yaxunit_tests (kind: read — it only walks the BSL AST and
returns a JSON envelope; it must NOT modify the git-tracked project tree).

What the tool does
------------------
ListYaxunitTestsTool enumerates the YAXUnit test suites of a project: a suite is
a common module whose methods carry the &Test/&Тест pragma (the parameterized
&ParametrizedTest/&ПараметрическийТест counts too), and each suite carries the
names of those test methods. The response is a JSON envelope
`{project, count, testCount, suites:[{modulePath, moduleType, parentName,
tests:[{name}]}]}`. A whole suite is run by feeding its modulePath into the
`modules` of run_yaxunit_tests; individual tests by feeding `Module.Method` into
its `tests`. The BSL AST is only READ, so a correct run leaves the fixture clean.
Every test here ends with assert_no_diff(); a tool that wrote into the project
tree would be a bug.

ENVIRONMENT (this EDT / fixture)
--------------------------------
In THIS environment TestConfiguration has a CommonModule.Calc whose methods carry
NO YAXUnit test pragmas, so a well-formed call returns a valid, EMPTY envelope
(count 0) — the tool never requires an infobase or a running application. That
empty-but-valid envelope IS the realistic happy contract here: it proves the AST
walk ran and produced a syntactically correct response without crashing.

Control-flow facts pinned from ListYaxunitTestsTool.java (so the asserts are
mutation-sensitive against the SPECIFIC message, not just is_error):

  execute():
    * no projectName
        -> JsonUtils.requireArgument: "projectName is required" (+ discovery hint)
    * projectName that does not resolve
        -> ProjectContext.notFoundMessage: "Project not found: <name> ..." (+
           actionable sibling-tool hint)

Parameter shape (from getInputSchema): projectName (str, required), limit (int,
default 200, clamped to max 1000 — optional).
"""

import json

from harness import (
    call,
    assert_error,
    assert_error_quality,
    assert_no_diff,
    e2e_test,
    PROJECT,
)


# ──────────────────────────────────────────────────────────────────────────────
# HAPPY / SENTINEL
#
# The realistic happy observation is the EMPTY but VALID envelope. We assert its
# full shape (JSON, echoed project, count == len(suites), testCount consistent,
# every suite well-formed), so the test fails if the tool returns a blank/bare
# error, a non-JSON report, or an envelope whose counts do not hold.
# ──────────────────────────────────────────────────────────────────────────────
@e2e_test(tool="list_yaxunit_tests", kind="read")
def test_wellformed_call_returns_valid_empty_envelope():
    """A well-formed call on TestConfiguration (whose common modules have no test
    pragmas) returns a VALID JSON envelope, not an error and not a blank.

    This is the REAL happy contract in this headless environment: the tool needs
    no infobase, so the AST walk must complete and hand back a syntactically
    correct response with the project echoed, count == len(suites), and
    testCount == the sum of the suites' test lists. A broken tool that returned
    a bare error, an empty body, or a count that disagrees with the suites array
    fails here."""
    r = call("list_yaxunit_tests", {"projectName": PROJECT})
    assert not r.is_error, "well-formed call must not error: " + r.error_text()
    payload = json.loads(r.text)
    assert isinstance(payload, dict), "envelope must be a JSON object"
    assert payload.get("project") == PROJECT, (
        "envelope must echo the project name, got: " + str(payload.get("project")))
    suites = payload.get("suites")
    assert isinstance(suites, list), "suites must be an array"
    assert payload.get("count") == len(suites), (
        "count must equal the number of suites: " + r.text)
    assert payload.get("testCount") == sum(len(s.get("tests", [])) for s in suites), (
        "testCount must equal the summed test lists: " + r.text)
    for s in suites:
        assert s.get("modulePath"), "every suite must carry a modulePath"
        assert isinstance(s.get("tests", []), list), "suite.tests must be an array"
    assert_no_diff("a list must never write into the project tree")


# ──────────────────────────────────────────────────────────────────────────────
# NEGATIVE — missing / unknown project
# ──────────────────────────────────────────────────────────────────────────────
@e2e_test(tool="list_yaxunit_tests", kind="read")
def test_missing_projectname_errors_on_required_argument():
    """No projectName supplied -> the first execute() guard fires:
    "projectName is required". The message names the parameter, and the canonical
    discovery hint points at the sibling tools that produce a valid value."""
    r = call("list_yaxunit_tests", {})
    err = assert_error(r, "missing projectName")
    assert_error_quality(
        err,
        names=["projectName"],
        ctx="missing projectName is reported as a required-argument error",
    )
    assert_no_diff("an invalid call must not touch the project on disk")


@e2e_test(tool="list_yaxunit_tests", kind="read")
def test_unknown_projectname_errors_with_not_found_message():
    """A non-existent project -> ProjectContext.notFoundMessage: the message must
    name the offending (bad) project value and be actionable (point at how to fix
    the name), not a bare/blank refusal."""
    bad = "NoSuchProject_ZZZ_e2e"
    r = call("list_yaxunit_tests", {"projectName": bad})
    err = assert_error(r, "non-existent project")
    assert_error_quality(
        err,
        names=[bad],
        ctx="an unknown project is named in the not-found error",
    )
    assert_no_diff("an invalid call must not touch the project on disk")
