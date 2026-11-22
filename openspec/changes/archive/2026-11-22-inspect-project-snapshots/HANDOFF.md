# Windows handoff: `inspect-project-snapshots`

See [Windows verification](WINDOWS-VERIFICATION.md) for the follow-up results on the dedicated Windows copy. The sections below record the original handoff.

## Resume here

Start OpenCode in the checkout and ask it to read this file, then run
`openspec-apply-change inspect-project-snapshots`. The `openspec` CLI must be
available on the new machine. The repo-local change is already present; do not
run `openspec init` over it. To inspect the change without the agent skill:

```powershell
openspec status --change inspect-project-snapshots --json
openspec instructions apply --change inspect-project-snapshots --json
openspec validate inspect-project-snapshots --strict
```

Read the returned context files: [proposal](proposal.md), [design](design.md),
[specification](specs/project-source-snapshots/spec.md), and [tasks](tasks.md).
Read [repository conventions](../../../CLAUDE.md) and the repo's
[build/test guide](../../../.claude/skills/edt-mcp-build-test/SKILL.md). This
handoff does not require the old OpenCode session, local caches, or a copy of
the external EDT plugin development pack. Use a dedicated Windows EDT test
installation and synthetic fixtures; do not use or modify a production EDT.

## Where work stopped

OpenSpec progress: **0/7**. Task **1.1** is in progress, not complete. The first
release targets **Windows EDT and local disks only**, not UNC paths or mapped
network drives. The implementation currently includes:

- `HttpTransport`, `McpHttpHandler`, `InterruptibleToolExecutor` and
  `McpProtocolHandler`: flow an authenticated loopback HTTP identity into the
  call. A caller-provided `Mcp-Session-Id`, an unauthenticated request or the
  in-process bridge cannot provide this identity. The snapshot gate also
  checks the actual listener binding and the current credential.
- `utils/snapshot/SnapshotSourcePolicy`: server-owned, deny-by-default project
  names, pinned EDT project locations, `src` scope, and extension allow-lists.
  The opt-in JVM property is `edt.mcp.snapshots.policy`. Its value is a JSON
  object keyed by exact EDT project name, for example
  `{"Example":{"location":"C:\\Dev\\Example","extensions":["mdo","bsl"]}}`.
  Keep real locations and the server token outside Git. For the remote test,
  use a protected route to the loopback listener with mandatory authentication;
  do not enable the plaintext all-interfaces listener as a workaround.
- `utils/snapshot/WindowsSourceTree`: JNA bindings for read-only access using
  Windows handles. No fallback to Java path-based reads. JNA is declared in
  the Tycho target, plug-in manifest and Windows feature packaging, but an
  actual Windows build and install have not yet verified the wiring.

No snapshot tool is registered yet. Git capture, model comparison and
fingerprints, storage/TTL/leases, pagination/diff, and live acceptance are
not implemented. Do not deploy this checkpoint as a completed feature.

## First action on Windows: finish task 1.1

1. Use the repository's Maven/Tycho toolchain and EDT 2026.1 target. Prefer the
   Maven 3.9.x / JDK 17 baseline documented by the repository and CI; if the
   target's class version or Tycho requires a different supported build JDK,
   record the actual toolchain. Do not change the target or dependency versions
   just to make the build run. Enable Windows symlink creation for the test
   account (Developer Mode or an appropriate privilege). Use a local test
   directory; a skipped or uncreated symlink fixture is not a pass.
2. From the repo root run the focused tests, then the full build. For example:

   ```powershell
   mvn -B -f mcp/pom.xml verify '-Dtest=WindowsSourceTreeTest,SnapshotSourcePolicyTest,HttpSnapshotAuthenticationTest'
   mvn -B -f mcp/pom.xml clean verify
   ```

   Confirm the Surefire report actually contains the Windows tests with no
   skips. Exercise a normal `.bsl` read/list, external symlink, and a junction
   inserted between enumeration and opening. Confirm that denied calls reveal
   neither external bytes nor host paths. Check the Windows package resolves
   JNA and the native APIs, not just that Java compiles. Add any missing checks
   for a neighboring project in the same Git repository, network-backed paths,
   and failure to load native access before closing 1.1.
3. If something fails, keep the gate fail-closed. Fix the issue and record the
   Windows test results. Check off 1.1 only after verification, and rerun the
   OpenSpec apply instructions to confirm progress.

## Continue after 1.1

- **1.2:** Use `GitRepositoryResolver` for HEAD and index, and the Windows
  reader for disk. Restrict tree/index entries before materializing blobs;
  handle staged and unstaged deltas separately, ignored/untracked files,
  deletions, and ambiguous renames. Never execute Git filters or cross a
  submodule/linked resource. Test a temporary repository with sibling projects.
- **1.3:** Fingerprint the configuration and explicitly included extensions.
  Check for changes across capture and distinguish `verified`, `diverged`, and
  `unknown`. Do not save an editor or call resync to make a capture pass.
- **2.x and 3.1:** Follow `tasks.md` for storage, paginated tools and remote
  no-clone acceptance. Keep the generic `git` tool disabled for acceptance.
  Check for accidental source/path disclosure in request history and logs
  when wiring the tools. Keep any acceptance transcript free of credentials
  and private project paths.

## Evidence already available

- `openspec validate inspect-project-snapshots --strict --json` passed.
- Changed Java and the three test classes compiled with `javac --release 17`
  against the locally installed EDT 2026.1 bundles and the plug-in's `lib/`
  jars (bundle-classpath jars first). JUnit ran **four non-native tests** from
  `SnapshotSourcePolicyTest` and `HttpSnapshotAuthenticationTest`, all passing.
- The canonical macOS build (`bash source/compile.sh`) stopped before Java
  compilation in Tycho 4.0.5, using Maven 3.10.0 and JDK 27:
  `No implementation for MavenTargetLocationFactory was bound`. This does not
  verify Tycho packaging, Windows native calls or the live MCP contract.
