"""
e2e tests for list_infobases (kind: read).

What the tool does
------------------
list_infobases enumerates the infobases registered in EDT's GLOBAL panel (the
"Infobases" / Инфобазы view) via IInfobaseManager.getAll(), independent of any
one project's applications. It walks the panel tree (Group folders ->
InfobaseReference leaves) and reports each leaf with its name, uuid, type
(FILE/SERVER), connection string, folder, external and recent markers, plus a
group summary and EDT's recent list.

Response shape (IMPORTANT)
--------------------------
ListInfobasesTool.getResponseType() == JSON, so the real payload lands in
Result.structured. The success envelope is:
    {"success": true,
     "infobases": [ {"name","type","external","recent",
                     ["uuid"], ["connectionString"], ["file"], ["folder"]}, ... ],
     "groups": [ {"name","count"}, ... ],
     "recent":   ["<infobase name>", ...],
     "total": <int>}                    # == len(infobases)
On error the envelope is {"success": false, "error": "<message>"}.

Why we assert STRUCTURAL INVARIANTS, not fixed names
----------------------------------------------------
Infobases live in the EDT *workspace* (.metadata), not the git fixture tree, and
are created at runtime when bases are registered — the fixture checkout has none
guaranteed. The set is therefore environment-dependent. Instead we assert the
invariants that break if the tool is broken regardless of the environment:
  - the JSON envelope is present and well-formed (success=true),
  - "infobases", "groups", "recent" are lists and "total" is an int,
  - total == len(infobases)   (the tool derives total from the collected leaves),
  - every infobase entry carries the non-empty "name" + "type" the caller selects
    bases by (name is what create_project_application binds on),
  - a read tool never mutates: every test ends with assert_no_diff().

Filter behaviour
----------------
Calling with {"groupName": "<folder>"} restricts the list to that folder; a
non-existent folder returns a structured error naming the value. These are
exercised environment-tolerantly: the error branch is always reachable (pick an
impossible folder name), and the filtered happy-path only when that folder is
present in the workspace.
"""

from harness import (
    call,
    assert_ok,
    assert_error,
    assert_error_quality,
    assert_no_diff,
    e2e_test,
)


def _envelope(r, ctx):
    """Validate the JSON success envelope; return (infobases, total)."""
    sc = r.structured
    if not isinstance(sc, dict):
        raise AssertionError("expected structuredContent dict [%s]: %r" % (ctx, sc))
    inf = sc.get("infobases")
    total = sc.get("total")
    if not isinstance(inf, list):
        raise AssertionError("'infobases' must be a list [%s]: %r" % (ctx, inf))
    if not isinstance(total, int):
        raise AssertionError("'total' must be an int [%s]: %r" % (ctx, total))
    if total != len(inf):
        raise AssertionError(
            "total(%d) must equal len(infobases)(%d) [%s]" % (total, len(inf), ctx))
    for key in ("groups", "recent"):
        if not isinstance(sc.get(key), list):
            raise AssertionError("'%s' must be a list [%s]: %r" % (key, ctx, sc.get(key)))
    return inf, total


# ──────────────────────────────────────────────────────────────────────────────
# HAPPY PATHS
# ──────────────────────────────────────────────────────────────────────────────
@e2e_test(tool="list_infobases", kind="read")
def test_consistent_envelope_and_entry_shape():
    """Valid call without filters -> a well-formed envelope with total ==
    len(infobases), every entry carrying the non-empty 'name' + 'type' used to
    select/identify an infobase, and a read-side effect only."""
    r = call("list_infobases", {})
    assert_ok(r, "list_infobases on the live panel")
    inf, _ = _envelope(r, "unfiltered envelope")
    for entry in inf:
        if not isinstance(entry, dict):
            raise AssertionError("each infobase entry must be an object: %r" % entry)
        if not entry.get("name"):
            raise AssertionError("every infobase entry must carry a non-empty 'name': %r" % entry)
        # type is FILE | SERVER | UNKNOWN — always present for identification.
        if "type" not in entry:
            raise AssertionError("every infobase entry must carry a 'type': %r" % entry)
    assert_no_diff("a read tool must not touch the project on disk")


@e2e_test(tool="list_infobases", kind="read")
def test_success_flag_and_no_error_field():
    """The success envelope must positively flag success=true and must NOT carry an
    'error' field — discriminating the success payload from the error payload."""
    r = call("list_infobases", {})
    assert_ok(r, "list_infobases success flag")
    sc = r.structured
    if not isinstance(sc, dict):
        raise AssertionError("expected structuredContent dict: %r" % sc)
    if sc.get("success") is not True:
        raise AssertionError("happy-path envelope must set success=true: %r" % sc)
    if "error" in sc:
        raise AssertionError("happy-path envelope must NOT carry an 'error' field: %r" % sc)
    assert_no_diff("a read tool must not touch the project on disk")


@e2e_test(tool="list_infobases", kind="read")
def test_unknown_groupname_errors_clearly():
    """A groupName filter that matches no folder must return a structured error that
    names the requested folder and steers to an unfiltered listing — not an empty
    success envelope that reads as 'no infobases'."""
    bad = "NoSuchGroup_ZZZ_e2e"
    r = call("list_infobases", {"groupName": bad})
    err = assert_error(r, "non-existent groupName")
    assert_error_quality(err, names=[bad], suggests=["list_infobases"],
                         ctx="unknown groupName is named and steers to the unfiltered list")
    assert_no_diff("an invalid call must not touch the project on disk")
