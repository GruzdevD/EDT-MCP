"""
e2e tests for vanessa_open_allure_report (kind: read; never mutates a project).

The tool resolves a run's raw Allure results (by launchId or outDir), optionally
generates the static report with the Allure CLI, serves it over loopback and
opens it in the in-EDT view (or the OS browser when detached=true). It NEVER
touches the EDT model, so every leg ends with assert_no_diff().

Why the NEGATIVE matrix is what it is:
  getInputSchema() declares launchId (one-of with outDir) + outDir + three
  optional flags. The reachable client-driven error paths, all deterministic
  without needing the Allure CLI or a real run:
    - neither launchId nor outDir        -> "Either outDir or a numeric launchId"
    - non-numeric launchId ("abc")       -> same "numeric launchId" error
    - numeric but unknown launchId       -> "Unknown launchId <n>"
    - outDir that does not exist         -> "No such out dir: <path>"
  The "outDir exists but has no *-result.json" and all happy paths need a real
  BDD run's results AND the Allure CLI, so they are covered by the guarded
  integration leg below and by the unit test (VanessaOpenAllureReportToolTest).

Why the HAPPY leg is guarded, not hard-failing:
  It needs a real prior Vanessa run's allure dir under
  ~/.1c-tools/vanessa/out/<proj>/allure plus an on-machine Allure CLI. Neither
  is guaranteed on every CI box, so the leg probes for both and documents a
  clean return when absent instead of failing coverage for an environment bit.
"""

from harness import (
    call, assert_ok, assert_error, assert_error_quality,
    assert_contains, assert_no_diff, e2e_test,
)

import glob
import os
import shutil
import urllib.request

HOME = os.path.expanduser("~")
# Raw Allure results of past VA runs: ~/.1c-tools/vanessa/out/<proj>/allure/*-result.json
_ALLURE_GLOB = os.path.join(HOME, ".1c-tools", "vanessa", "out", "*", "allure", "*-result.json")


def _first_existing_out_dir():
    hits = sorted(glob.glob(_ALLURE_GLOB))
    # Convert "<out>/<proj>/allure/<file>" back to the run out dir "<out>/<proj>".
    return os.path.dirname(os.path.dirname(hits[0])) if hits else None


def _allure_cli_present():
    for cand in (os.path.join(HOME, ".1c-tools", "allure"), shutil.which("allure")):
        if cand and os.path.exists(cand):
            return True
    return False


# ──────────────────────────────────────────────────────────────────────────────
# Negative matrix (deterministic, no external deps)
# ──────────────────────────────────────────────────────────────────────────────

@e2e_test(tool="vanessa_open_allure_report", kind="read")
def test_no_selector_is_error_and_does_not_mutate():
    r = call("vanessa_open_allure_report", {})
    assert_error(r, "no launchId/outDir must be an error")
    assert_error_quality(r.structured.get("error", ""),
                         names=["outDir", "launchId"],
                         ctx="neither outDir nor launchId")
    assert_no_diff("must not mutate")


@e2e_test(tool="vanessa_open_allure_report", kind="read")
def test_non_numeric_launch_id_is_error():
    r = call("vanessa_open_allure_report", {"launchId": "abc"})
    assert_error(r, "non-numeric launchId must be an error")
    assert_contains(r.structured.get("error", ""), "launchId",
                    "error must point at the launchId field")
    assert_no_diff("must not mutate")


@e2e_test(tool="vanessa_open_allure_report", kind="read")
def test_unknown_launch_id_is_error():
    r = call("vanessa_open_allure_report", {"launchId": "999999999"})
    assert_error(r, "unknown launchId must be an error")
    assert_contains(r.structured.get("error", ""), "Unknown launchId",
                    "error must say the launchId is unknown")
    assert_no_diff("must not mutate")


@e2e_test(tool="vanessa_open_allure_report", kind="read")
def test_missing_out_dir_is_error():
    r = call("vanessa_open_allure_report", {"outDir": "/no/such/dir"})
    assert_error(r, "nonexistent outDir must be an error")
    assert_contains(r.structured.get("error", ""), "No such out dir",
                    "error must name the missing outDir")
    assert_no_diff("must not mutate")


# ──────────────────────────────────────────────────────────────────────────────
# Guarded integration leg (needs a real prior VA run's allure results + Allure CLI)
# ──────────────────────────────────────────────────────────────────────────────

@e2e_test(tool="vanessa_open_allure_report", kind="read")
def test_generate_open_serves_report_when_data_present():
    out_dir = _first_existing_out_dir()
    if not out_dir:
        # No prior VA run to report on this box -> environment bit absent, not a
        # tool defect. Documented early return; negatives above keep coverage.
        return
    if not _allure_cli_present():
        # No Allure CLI -> generation would error with the actionable install hint;
        # out of scope here (covered by Tier-2 live check).
        return

    r = call("vanessa_open_allure_report", {"outDir": out_dir, "detached": "true"})
    assert_ok(r, "happy path must succeed with detached=true (no EDT UI dependency)")

    url = r.structured.get("url", "")
    assert url, "success must expose the served report url"
    assert url.startswith("http://127.0.0.1:"), "url must be loopback, got %r" % url
    assert_contains(r.structured.get("resultsDir", ""), "allure",
                    "resultsDir must point at the run's allure results")

    # The served 127.0.0.1:port must actually answer on /index.html.
    try:
        with urllib.request.urlopen(url + "index.html", timeout=15) as resp:
            code = resp.getcode()
            body = resp.read(300).decode("utf-8", "replace")
    except Exception as exc:  # noqa: BLE001 - transport probe, fail loudly
        raise AssertionError("Allure report url %r did not serve: %s" % (url, exc)) from exc
    assert code == 200, "index.html must serve 200, got %d" % code
    body_lower = body.lower()
    assert ("<!doctype html" in body_lower or "<html" in body_lower), \
        "index.html must be an HTML report, got: %r" % body[:120]

    assert_no_diff("must not mutate")
