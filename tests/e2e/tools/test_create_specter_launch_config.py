"""
e2e tests for create_specter_launch_config (kind: action).

THE TOOL (CreateSpecterLaunchConfigTool, getResponseType() == JSON):
Creates a Specter UI-testing launch configuration (type ru.ozon.uitp.e2e.launcher.specter)
that references a base EDT runtime-client launch configuration. Requires the Specter EDT
plugin (ru.ozon.uitp.e2e) to be installed; otherwise the tool reports the unregistered type.

FIXTURE CONTEXT:
Launch configs live in workspace .metadata, NOT in the git-tracked TestConfiguration/ tree.
assert_no_diff does NOT catch leaked configs. delete_launch_config (which finds any EDT
config by name) is used in a finally block to remove anything created by a happy-path test.

ENVIRONMENT CONDITIONAL:
- If the Specter plugin is NOT installed: every call fails with the 'type not registered'
  error — tests assert that error's quality honestly.
- If it IS installed: the happy path needs a base RuntimeClient config. If the workspace
  has none, the tool's 'base not found' error path is asserted instead. Neither
  environment fakes a green result.
"""

from harness import (
    call,
    assert_ok,
    assert_error,
    assert_error_quality,
    assert_no_diff,
    e2e_test,
    PROJECT,
)

_CONFIG_NAME = "e2e_CreateSpecterLaunchConfigTest_Specter"
_MISSING_PLUGIN_MARKER = "ru.ozon.uitp.e2e.launcher.specter"


def _ensure_config_absent():
    """Best-effort pre/post clean: remove a leftover config from a prior crashed run."""
    call("delete_launch_config", {"name": _CONFIG_NAME, "confirm": True})


# ──────────────────────────────────────────────────────────────────────────────
# Required-argument guard (works in both environments)
# ──────────────────────────────────────────────────────────────────────────────
@e2e_test(tool="create_specter_launch_config", kind="action")
def test_missing_base_launch_config_errors():
    """No baseLaunchConfig -> the shared required-arg guard fires and names the param."""
    r = call("create_specter_launch_config", {})
    e = assert_error(r, "missing baseLaunchConfig")
    assert_error_quality(
        e,
        names=["baseLaunchConfig"],
        ctx="missing baseLaunchConfig names the parameter",
    )
    assert_no_diff("a rejected call must not touch the fixture")


@e2e_test(tool="create_specter_launch_config", kind="action")
def test_plugin_missing_or_base_rejected():
    """
    A nonexistent base config must produce an actionable error. Two honest outcomes:
    - Specter plugin absent -> 'type ... not registered' error naming the plugin;
    - Specter plugin present -> 'base launch configuration not found' error naming
      the bad name and steering to list_configurations.
    """
    bad_base = "e2e_NoSuchBase_clc_zzz"
    r = call("create_specter_launch_config", {"baseLaunchConfig": bad_base,
                                              "name": _CONFIG_NAME})
    e = assert_error(r, "nonexistent base or missing plugin")
    msg = str(e)
    if _MISSING_PLUGIN_MARKER in msg and "not registered" in msg:
        # Plugin absent in this EDT — assert the actionable plugin hint.
        assert "ru.ozon.uitp.e2e" in msg, \
            "plugin-absent error must name the Specter plugin bundle: %r" % msg
    else:
        assert_error_quality(
            e,
            names=[bad_base],
            suggests=["list_configurations"],
            ctx="missing base names the bad config and steers to list_configurations",
        )
    assert_no_diff("a rejected call must not touch the fixture")


# ──────────────────────────────────────────────────────────────────────────────
# Happy path (needs Specter plugin + a base RuntimeClient config)
# ──────────────────────────────────────────────────────────────────────────────
@e2e_test(tool="create_specter_launch_config", kind="action")
def test_create_specter_config_happy_path():
    """
    Full round-trip: create -> delete_launch_config cleanup. Requires the Specter
    plugin AND at least one RuntimeClient config in the workspace; asserts the
    honest error path when either precondition is unmet.
    """
    _ensure_config_absent()
    try:
        lc = call("list_configurations", {"type": "client"})
        base = None
        if lc.structured and isinstance(lc.structured, dict):
            configs = lc.structured.get("configurations", [])
            for c in configs:
                name = c.get("name", "")
                if not name.startswith("Specter:"):
                    base = name
                    break

        r = call("create_specter_launch_config", {
            "baseLaunchConfig": base or "e2e_NoSuchBase_clc_zzz",
            "name": _CONFIG_NAME,
            "testingPort": 4811,
        })

        if base is None:
            # No base config available: the tool must reject with the base-not-found error.
            e = assert_error(r, "no-base-config path: tool must reject with actionable error")
            assert_error_quality(
                e,
                suggests=["list_configurations"],
                ctx="no runtime-client config: error must steer to list_configurations",
            )
            assert_no_diff("a rejected call must not touch the fixture")
            return

        assert_ok(r, "create_specter_launch_config happy path")
        sc = r.structured
        assert sc is not None and isinstance(sc, dict), \
            "create must return structuredContent dict"
        assert sc.get("action") == "created", \
            "success response must have action='created', got: %r" % sc.get("action")
        assert sc.get("name") == _CONFIG_NAME, \
            "returned name must match requested name: %r" % sc.get("name")
        assert sc.get("baseLaunchConfig") == base, \
            "returned baseLaunchConfig must match: %r" % sc.get("baseLaunchConfig")
        assert sc.get("testingPort") == 4811, \
            "returned testingPort must echo 4811: %r" % sc.get("testingPort")
        assert sc.get("type") == _MISSING_PLUGIN_MARKER, \
            "returned type must be the Specter launch type id: %r" % sc.get("type")

        assert_no_diff("create must not touch the git-tracked fixture tree")
    finally:
        # Always clean up — configs are in workspace .metadata and are not git-tracked.
        _ensure_config_absent()


@e2e_test(tool="create_specter_launch_config", kind="action")
def test_duplicate_name_rejected():
    """
    Creating the same named config twice must be rejected the second time with a
    list_configurations hint (only reachable when the plugin + base are present;
    otherwise the earlier gate fires and this path is honestly skipped).
    """
    _ensure_config_absent()
    try:
        lc = call("list_configurations", {"type": "client"})
        base = None
        if lc.structured and isinstance(lc.structured, dict):
            configs = lc.structured.get("configurations", [])
            for c in configs:
                name = c.get("name", "")
                if not name.startswith("Specter:"):
                    base = name
                    break
        if base is None:
            return  # cannot create the first config; earlier tests assert the error

        r1 = call("create_specter_launch_config", {"baseLaunchConfig": base,
                                                   "name": _CONFIG_NAME})
        assert_ok(r1, "first create must succeed")

        r2 = call("create_specter_launch_config", {"baseLaunchConfig": base,
                                                   "name": _CONFIG_NAME})
        e = assert_error(r2, "duplicate name must be rejected")
        assert_error_quality(
            e,
            names=[_CONFIG_NAME],
            suggests=["list_configurations"],
            ctx="duplicate name error must name the config and steer to list_configurations",
        )
    finally:
        _ensure_config_absent()
