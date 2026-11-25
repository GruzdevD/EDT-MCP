# Tasks

## 1. Windows fixture and isolated build

- [x] 1.1 Prepare the isolated Windows EDT copy with the existing `TestConfiguration` and `tests` projects and a dedicated local file infobase. Install the YAxUnit EDT plug-in and matching engine if missing, apply `tests`, and record their versions and engine identity. Verify the existing thin-client configuration runs `tests_SampleTests.CallsBaseConfigCalcModule` and yields a passing JUnit result.
- [x] 1.2 Verify the isolated toolchain on this Windows stand. Export a private copy of the existing EDT projects with `1cedtcli`, provision a pinned engine-only template, and prove that Designer and the thin client run the fixture in a separate file infobase.

## 2. Capture and admission

- [x] 2.1 Retain the optional Windows build payload under snapshot quotas. Verify complete inputs, descriptor and extension validation, unchanged-file retention, safe reads, and refusal for a changing or incomplete tree.
- [x] 2.2 Add snapshot-bound admission to `run_yaxunit_tests`. Test route authentication, snapshot ownership and expiry, execution grants, invalid parameter combinations, and unchanged legacy calls.

## 3. Isolated execution and evidence

- [x] 3.1 Build and execute each bound run in a unique private workspace and file infobase, using the pinned YAxUnit template. Test applied base and extension artifacts, missing inputs, cancellation, and cleanup.
- [x] 3.2 Enforce owner and current grant access on bound jobs and redact sensitive protocol payloads. Test foreign polling and cancellation, isolation across runs, and fail-closed execution errors.
- [x] 3.3 Return the same snapshot evidence for a completed start call or poll. Verify executed-test counts, genuine assertion failures, empty reports and cancellations. Update tool guides and reference documentation.

## 4. EDT acceptance

- [x] 4.1 Run capture-to-report acceptance on the existing `TestConfiguration` and `tests` projects. Verify that the earlier capture still passes after an uncommitted `Calc.Add` edit, the newer capture reports the changed behavior, the deliberate failure demo reports its expected counts, and the temporary jobs leave the stand's linked infobase untouched.
- [x] 4.2 Run the build, automated tests and live Windows checks. Record the fixture revision, CLI and engine versions, selected tests, JUnit outcomes, and relevant EDT log findings.
