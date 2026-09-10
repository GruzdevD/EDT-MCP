"""
e2e tests for vanessa_setup (provisions a project's .vanessa/ tree).

WHAT THE TOOL DOES
------------------
vanessa_setup provisions one project's Vanessa Automation turnkey: it creates the
.vanessa/ layout in the EDT project root (env.sh, VAParams.json, features/, out/)
and, when the vanessa-automation.epf runtime is missing, starts a background job
that downloads it (plus locales/) into the shared cache
~/.1c-tools/vanessa/va/<version>/ and points EPF= in env.sh at it. Optionally
also installs the Allure CLI (installAllure). Existing edited files are never
overwritten; rerunning is idempotent.

WHY THE e2e IS DELIBERATELY NARROW
----------------------------------
The happy path of vanessa_setup is inherently environment-bound and mutating:
for ANY supplied project key it writes the .vanessa/ layout (into the EDT
project root, or the legacy ~/.1c-tools/vanessa/projects/<project> home dir) and
— unless the epf is already cached — kicks off a real ~31MB network download. An
e2e that exercised it would pollute the fixture project AND/OR hit the network on
a bare CI box, so it is deliberately NOT covered here; the layout/provision logic
is instead covered by the pure unit tests (VanessaBootstrapTest, VanessaSetupToolTest)
and the readiness/downlord flow is verified live in Tier-2/manual checkout.

This file therefore registers only the deterministic, side-effect-free error
path (project omitted → refuses before any write), which keeps the e2e coverage
ratchet satisfied without a mutating/network-dependent leg.

RESPONSE / ERROR CONTRACT
-------------------------
On failure returns ToolResult.error(<msg>) -> {"success":false,"error":<msg>},
machine-detectable via r.is_error. The deterministic, write-free error is:
  - project omitted            -> "project is required."
"""

from harness import call, assert_error, assert_contains, e2e_test


@e2e_test(tool="vanessa_setup", kind="read")
def test_no_project_error():
    r = call("vanessa_setup", {})
    assert_error(r, "vanessa_setup without project must refuse")
    assert_contains(r.error_text(), "project is required.", "no-project message")
