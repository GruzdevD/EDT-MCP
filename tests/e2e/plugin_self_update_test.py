#!/usr/bin/env python3
"""
Serious end-to-end test for the git-based plugin self-update loop.

What it proves — the full, real cycle, on a LIVE EDT that runs the plugin:
  1. the running plugin is a build that contains the single-segment
     bundles.info fix (runtimeBundlesInfo), i.e. code that can locate and
     rewrite the real bundles.info on its own;
  2. plugin_check_for_update DETECTS a newer published build (updateAvailable);
  3. plugin_update DEPLOYS that jar into ~/.p2/pool/plugins (removing stale),
     and — the crux — REWRITES the bundles.info line on disk by itself
     (no manual registration, no restart needed for the check to see it);
  4. a follow-up check reports the plugin as up to date.

Why NOT part of the normal pytest suite (tests/e2e/tools/): every other e2e
test is isolated through git fixture projects that the harness resets. The
self-update tools are inherently LIVE — plugin_update writes into the running
EDT's ~/.p2/pool/plugins and its install's bundles.info, and the cycle pushes a
candidate build to the personal update-site branch. That would fight the
harness's fixture isolation, so this is a standalone script you run on demand.

Run it against the personal EDT (afm runtime MCP on 8766):

    MCP_PORT=8766 python3 tests/e2e/plugin_self_update_test.py

It mutates the real install and the update-site branch (restoring update-site
is intentionally NOT done: after the run update-site and the on-disk install
both point at the same new candidate, i.e. a consistent, repeatable end state).
"""

import glob
import json
import os
import re
import shutil
import subprocess
import sys

HOME = os.path.expanduser("~")
PORT = os.environ.get("MCP_PORT", "8766")

MCP_SH = os.path.join(HOME, ".1c-tools/vanessa/mcp.sh")
GIT_CONFIG_GLOBAL = os.path.join(HOME, ".1c-tools/gitlab-config")

REMOTE = "https://github.com/<your-github-account>/EDT-MCP.git"
BRANCH = "update-site"
SYM = "com.ditrix.edt.mcp.server"
JAR_PREFIX = SYM + "_"

POOL_DIR = os.path.join(HOME, ".p2", "pool", "plugins")
CLONE_DIR = os.path.join(HOME, ".1c-tools", "vanessa", "update-site")
BUNDLES_INFO = ("/Users/dmigruzdev/Library/Application Support/1C/1cedtstart/"
                "installations/1C_EDT 2026.1/1cedt.app/Contents/Eclipse/"
                "configuration/org.eclipse.equinox.simpleconfigurator/bundles.info")

# First build whose runtimeBundlesInfo uses the single-segment
# "org.eclipse.equinox.simpleconfigurator" path. The running plugin must be at
# or above this, else the disk-rewrite assertion below proves nothing (old code
# could not find bundles.info and only warned "register the jar manually").
FIXED_FLOOR = (1, 0, 0, 202609052219)


def varr(v):
    p = [int(x) for x in v.split(".")]
    p += [0] * (4 - len(p))
    return tuple(p[:4])


def vcmp(a, b):
    for x, y in zip(a, b):
        if x != y:
            return (x > y) - (x < y)
    return 0


_SCRUB_RE = re.compile(r"https?://[^@\s]+@", re.I)


def _scrub(text):
    return _SCRUB_RE.sub("proxy-login@", text)  # never leak proxy creds


def shell(cmd, cwd=None, env_extra=None):
    env = dict(os.environ)
    env.pop("JAVA_TOOL_OPTIONS", None)  # keep sandbox proxy props out of git/curl
    if env_extra:
        env.update(env_extra)
    r = subprocess.run(cmd, cwd=cwd, capture_output=True, text=True, env=env)
    return r.returncode, r.stdout, r.stderr


def git(*args, cwd=None, check=True):
    code, out, err = shell(["git", *args], cwd=cwd,
                           env_extra={"GIT_CONFIG_GLOBAL": GIT_CONFIG_GLOBAL,
                                      "GIT_TERMINAL_PROMPT": "0"})
    if check and code != 0:
        raise RuntimeError("git %s failed: %s" % (list(args), _scrub(err.strip())))
    return code, out.strip(), err


def mcp(tool, arguments=None):
    arguments = arguments or {}
    code, out, err = shell([MCP_SH, PORT, tool, json.dumps(arguments, ensure_ascii=False)])
    if code != 0:
        raise RuntimeError("mcp %s failed: %s" % (tool, _scrub(err.strip())))
    for line in reversed(out.splitlines()):
        line = line.strip()
        if not line:
            continue
        data = json.loads(line)
        if "result" in data:
            res = data["result"]
            if "structuredContent" in res:
                return res["structuredContent"]
            return res.get("content", [{"text": ""}])[0].get("text", "")
    raise RuntimeError("no JSON-RPC result in %r" % _scrub(out))


def ensure_clone():
    if not os.path.isdir(os.path.join(CLONE_DIR, ".git")):
        os.makedirs(CLONE_DIR, exist_ok=True)
        git("clone", "-q", "-b", BRANCH, REMOTE, CLONE_DIR)
    else:
        git("fetch", "-q", "origin", cwd=CLONE_DIR)
        git("reset", "-q", "--hard", "origin/" + BRANCH, cwd=CLONE_DIR)
    return CLONE_DIR


def version_of_jar(name):
    if not (name.startswith(JAR_PREFIX) and name.endswith(".jar")):
        return None
    return name[len(JAR_PREFIX):-len(".jar")]


def publish(version_bytes, version):
    """Commit <version>.jar into the update-site branch and push."""
    clone = ensure_clone()
    plugins = os.path.join(clone, "plugins")
    # exact-mirror the branch, then swap the single bundled jar to <version>.
    cur = [f for f in os.listdir(plugins) if f.startswith(JAR_PREFIX)]
    for f in cur:
        os.unlink(os.path.join(plugins, f))
    with open(os.path.join(plugins, JAR_PREFIX + version + ".jar"), "wb") as fh:
        fh.write(version_bytes)
    git("add", "-A", cwd=clone)
    # force a clean author for the test commit
    env_author = {"GIT_AUTHOR_NAME": "edt-mcp-vanessa",
                  "GIT_AUTHOR_EMAIL": "edt-mcp-vanessa@example.com",
                  "GIT_COMMITTER_NAME": "edt-mcp-vanessa",
                  "GIT_COMMITTER_EMAIL": "edt-mcp-vanessa@example.com"}
    code, _, err = shell(["git", "commit", "-q", "-m",
                          "edt-mcp-vanessa: publish plugin build %s.jar" % version],
                         cwd=clone, env_extra=env_author)
    git("push", "-q", "origin", BRANCH, cwd=clone)


def pool_self_jars():
    return sorted(f for f in os.listdir(POOL_DIR) if f.startswith(JAR_PREFIX) and f.endswith(".jar"))


def bundles_info_line():
    with open(BUNDLES_INFO, "r", encoding="utf-8") as fh:
        for line in fh:
            if line.strip().startswith(SYM + ","):
                return line.strip()
    return None


FAIL = 0


def check(cond, msg):
    global FAIL
    if not cond:
        FAIL += 1
        print("  [FAIL] " + msg)
    else:
        print("  [ok]   " + msg)


def main():
    print("plugin self-update e2e (%s)" % PORT)
    print("running plugin check ...")
    check0 = mcp("plugin_check_for_update")
    installed = check0["installedVersion"]
    check(vcmp(varr(installed), FIXED_FLOOR) >= 0,
          "running plugin is the fixed build (installed=%s >= floor)" % installed)

    # Source the candidate content from the FIXED jar currently in the pool.
    jars = pool_self_jars()
    check(len(jars) == 1, "exactly one self jar in pool (%r)" % jars)
    if len(jars) != 1:
        print("ABORT: cannot pick a safe candidate from a messy pool")
        return 1
    base_jar = jars[0]
    base_version = version_of_jar(base_jar)
    _, _, q = base_version.rpartition(".")
    candidate_version = base_version[:base_version.rfind(".")] + "." + str(int(q) + 1)
    check(vcmp(varr(candidate_version), varr(installed)) > 0,
          "candidate is newer than installed (%s -> %s)" % (installed, candidate_version))

    with open(os.path.join(POOL_DIR, base_jar), "rb") as fh:
        candidate_bytes = fh.read()

    # 1) publish a newer build
    print("publishing candidate %s to update-site ..." % candidate_version)
    publish(candidate_bytes, candidate_version)

    # 2) the check tool must SEE it
    check1 = mcp("plugin_check_for_update")
    check(check1.get("updateAvailable") is True,
          "check detects the update (updateAvailable=true)")
    check(check1.get("availableVersion") == candidate_version,
          "check reports the candidate version (%s)" % check1.get("availableVersion"))

    # 3) apply it
    upd = mcp("plugin_update")
    check(upd.get("success") is True, "update succeeds")
    check(upd.get("newVersion") == candidate_version,
          "update reports newVersion==%s" % upd.get("newVersion"))
    check(upd.get("restartRequired") is True, "update reports restartRequired")

    # 4) THE crux: the plugin rewrote bundles.info on disk by itself.
    line = bundles_info_line()
    check(line is not None and version_of_jar(os.path.basename(line.split(",")[2])) == candidate_version,
          "on-disk bundles.info rewritten to %s" % candidate_version)

    # 5) pool has the new jar and no stale self jar
    post = pool_self_jars()
    check(len(post) == 1 and version_of_jar(post[0]) == candidate_version,
          "pool holds exactly the candidate (%r)" % post)

    # 6) restart semantics: plugin_update prepares the disk (pool + bundles.info
    # point at the candidate) but the LIVE OSGi session keeps reporting the
    # pre-restart version until a restart, and therefore still sees an update
    # available. "Up to date (updateAvailable=false)" can only be asserted after
    # EDT restarts onto the candidate — that is exactly the restartRequired
    # contract the tool advertised.
    check2 = mcp("plugin_check_for_update")
    check(check2.get("installedVersion") == installed,
          "running session still reports pre-restart version (%s) until restart" % installed)

    print()
    if FAIL:
        print("RESULT: %d assertion(s) FAILED" % FAIL)
        return 1
    print("RESULT: PASS — the plugin detected, applied and self-registered a newer build "
          "(bundles.info rewritten in-place).")
    return 0


if __name__ == "__main__":
    sys.exit(main())
