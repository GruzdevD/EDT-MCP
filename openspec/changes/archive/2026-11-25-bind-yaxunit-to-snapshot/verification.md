# Windows verification

## Fixture and toolchain

- The dedicated stand used 1C:EDT and `1cedtcli` 2026.2.2 with 1C:Enterprise 8.5.1.1529. The installed YAxUnit EDT plug-in was 25.1.0.v20250204-1044 and the runtime engine was YAXUNIT 25.12.
- The base and test extension came from the existing repository fixtures at `eee05763`. The disposable stand commit `6a197e8f40762dd9a9fd34dde5c716a7847afd5e` adds the Russian language adopted by the stock engine. The repository fixture files retain their original contents.
- The template was a closed file infobase with the engine extension and the language prerequisites. The engine extension was the only extension before the build. The template SHA-256 was `15a7d42d58599dd20c99b8b1b9c8a98a210e2b21c952cefd29f6c3cca1975578`.
- The preflight exported both fixtures with EDT CLI, deployed them with Designer in a fresh copy of the engine template, and obtained a passing JUnit file from the thin client. The existing EDT-linked launch also passed its baseline test.

## Authenticated capture and run

| Captured inputs and selection | Passed | Failed | Errors |
| --- | ---: | ---: | ---: |
| Original `TestConfiguration` and `tests` after the working tree had been edited, `tests_SampleTests.CallsBaseConfigCalcModule` | 1 | 0 | 0 |
| New capture with an uncommitted `Calc.Add` change, same test | 0 | 1 | 0 |
| Original capture, `tests_FailureDemo` | 1 | 1 | 0 |

Both captures reported `yaxunitReady:true`, even though the live model status was `unknown`. The newer report included the expected value `5`, the actual value `12`, and a changed base fingerprint. Reusing the original capture for the demonstration created a fresh job. The run directory was removed at the end of each job. The `Calc` source was restored byte for byte.

A capture without the build payload and an extension with an invalid base declaration were rejected. A concurrent duplicate submission received the busy response. Cancelling the running export stopped the job and removed its directory. A selector that matched no tests failed at JUnit verification without reporting execution evidence.

The three acceptance runs were repeated with the linked original infobase held open for shared read access only. Its before and after SHA-256 values were identical, `cc0f97311b810550f985378bea3b7d078da420dfccba53f5a435c6ae6eee97b5`. An earlier comparison observed a different file hash while the EDT application agent was still connected. The repeat used a write lock to isolate the effect.

The EDT log on the dedicated capture workspace showed startup diagnostics from Eclipse Navigator duplicate extensions and working-set filters, and a Windows Defender service-state diagnostic. The bound executions completed with no plug-in error entries in that log.

## Automated checks

- `mvn -f mcp/pom.xml clean verify` passed with 7,420 tests reported, zero failures, zero errors, and 10 skipped. The reactor packaged the p2 repository.
- The targeted live MCP tests passed for the full `tools/list` golden, invalid snapshot arguments, the legacy missing-launch response, and the selected YAxUnit test with retained fingerprints.
- OpenSpec strict validation passed. The separate agent-pack validator reports CRLF in existing `agent/` files, including when run from the archived branch baseline.

## Review regressions

- The strict report tests cover cases outside suites, misplaced outcomes, incorrect aggregate or suite counts, and valid reports with pass, failure, error, and skip results. The legacy parser has a separate compatibility assertion.
- On the Windows stand, capture refused an empty `Configuration.mdo` and a missing `Calc.mdo` declared by the configuration. An allowed source listed in Git's exclude file appeared in a build capture and was filtered in the ordinary inspection capture. The fixture files and the exclude file were restored.
- Registry tests advance a controlled clock past the snapshot TTL while work is queued. They check that the accepted job can still read the retained data and that cancelling a queued job releases the expired payload. Policy and page tests exercise inspection and comparison across execution-grant changes.
- The native launch test checks that the creation call receives the Job Object through extended startup information. A separate Windows probe forcibly terminated its owning JVM and verified that both the command process and its child exited.
- The patched stand reproduced the same three JUnit outcomes with an identical before and after hash for the linked infobase. The cancellation and empty-report cases left no temporary job directories. The MCP admission tests and the `tools/list` golden passed.
- After the review fixes, `mvn -f mcp/pom.xml clean verify` reported 7,427 tests with zero failures, zero errors, and 10 skipped. OpenSpec strict validation and `git diff --check` passed.
- The follow-up review identified an additional case involving a nested subsystem's separate descriptor. The regression checks missing descriptors at two depths. The Windows stand refused capture with the declared child file absent, then accepted and ran the complete snapshot after the working tree was restored. The selected test passed and the linked infobase hash remained identical. The final focused Maven run reported 213 tests with zero failures, errors, or skips.
