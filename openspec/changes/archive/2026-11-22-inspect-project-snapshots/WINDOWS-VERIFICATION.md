# Windows verification

OpenSpec progress is **7/7**. The remote acceptance evidence is in [REMOTE-ACCEPTANCE.json](REMOTE-ACCEPTANCE.json).

## Automated checks

- Maven 3.9.11, Corretto JDK 17, Tycho 4.0.5, and the existing EDT 2026.1 target built the Windows p2 archive. The focused build passed **107 tests with zero failures, errors, or skips** after the Git metadata and transport tracing checks were added.
- The full suite before the snapshot implementation ran 7,398 tests. The rerun with the registered tools ran 7,412. Both reported the same six failing methods in `GuideCoverageTest`, `ToolGroupCoverageTest`, `BuiltInToolOutputSchemaTest`, `VanessaGetArtifactsToolTest`, `ToolsetsTest`, and `PreLaunchChangeTrackerTest`, with ten skips in each full run.
- After the tool catalogue and test fixture fixes, a fresh Windows `mvn clean verify` succeeded with **7,413 tests, zero failures or errors, and ten platform-specific skips**. Nine skipped cases require POSIX permissions. One requires a backslash in a filename. All three `WindowsSourceTreeTest` cases ran and passed, including the external symlink and the junction inserted after the project was opened.
- The final p2 package passed 108 focused tests with zero skips. After installation on the isolated EDT copy, the authenticated client verified the `tools/list` golden, all 12 new output schemas, the new guide and toolset entries, four read-only Vanessa calls, and four Allure error cases.

## Live loopback check

An isolated Windows EDT 2026.2.2 copy loaded p2 feature `3.0.0.202611211901` and opened a synthetic configuration in a local Git work tree. The client ran from a directory without a clone and authenticated to the loopback listener. The general `git` tool was disabled.

1. The client took a snapshot with one staged `.mdo` and one new `.bsl`. With a limit of one, page one returned the staged file and a cursor. Its diff contained the staged change.
2. After the stand changed the `.bsl` on disk, the client used the first snapshot's cursor to read page two. The old diff still contained the original `.bsl` text. A new snapshot had a different fingerprint, and the comparison contained the change to the `.bsl`.
3. The server rejected an unknown ID and a cursor presented with a different snapshot. The history retained tool-call metadata while omitting snapshot IDs and diff text. With HTTP debug tracing active, ordinary requests produced trace lines and snapshot payloads remained absent. The live model status was `unknown`.

## Remote acceptance from a nested guest

On 22 November 2026, the same Windows EDT stand served a client running in an independent Alpine Linux guest. QEMU software emulation provided the guest's kernel and virtual network. The client used only the acceptance scripts and temporary credentials in a directory outside any Git clone.

The guest reached a loopback-bound Windows relay over TLS and validated its certificate against a temporary CA. The relay forwarded MCP calls to the authenticated loopback listener. The client observed HTTP 401 without a token and verified that `tools/list` excluded the general `git` tool.

The first snapshot returned the staged `.mdo` on page one. After the stand changed the untracked `.bsl`, the first snapshot's cursor returned that file on page two and its diff retained the original text. The second snapshot had a different fingerprint, and the comparison showed both the old and the new BSL lines. The runtime returned `modelStatus: unknown`.
