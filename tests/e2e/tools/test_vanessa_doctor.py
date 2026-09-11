"""
e2e tests for vanessa_doctor (kind: read; never mutates a project).

WHAT THE TOOL DOES
------------------
vanessa_doctor is a READ-ONLY readiness report for one project's Vanessa
Automation setup. It checks the project's .vanessa/ layout (falling back to the
legacy ~/.1c-tools/vanessa/projects/<project>/env.sh) and the shared runtime
cache, and renders a Markdown checklist. It NEVER downloads anything, never
touches the workspace/EDT model, and never mutates files — so every leg ends
with assert_no_diff() (a doctor that mutated the project would be a bug).

RESPONSE / ERROR CONTRACT
-------------------------
getResponseType() is MARKDOWN. On success the Markdown report lands in the
markdown body. EVERY failure path returns ToolResult.error(<msg>).toJson() ->
{"success":false,"error":<msg>}; the protocol handler diverts success:false to
a structured error, so a failure is machine-detectable via r.is_error /
r.error_text().

The only reachable client-driven error (deterministic, no workspace needed):
  - project omitted            -> "project is required."

The happy path needs no environment: doctor returns a report for ANY project key
(even an unknown one → a "Not ready." checklist), so it is deterministic even on
a bare CI box. It never errors for a supplied project string.
"""

from harness import call, assert_error, assert_contains, assert_no_diff, e2e_test


@e2e_test(tool="vanessa_doctor", kind="read")
def test_no_project_error():
    r = call("vanessa_doctor", {})
    assert_error(r, "vanessa_doctor without project must refuse")
    assert_contains(r.error_text(), "project is required.", "no-project message")


@e2e_test(tool="vanessa_doctor", kind="read")
def test_reports_readiness_for_any_project():
    # Doctor never mutates and never touches the network, so running it on a bare
    # project key is safe in any environment and returns the Markdown headline.
    r = call("vanessa_doctor", {"project": "afm"})
    assert_contains(r.text, "# Vanessa doctor: afm", "doctor headline")
    # The report is a checklist (markdown body), not an error, on any box.
    assert_no_diff("vanessa_doctor must not mutate the project")
