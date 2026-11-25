# Design

## Context

The Windows stand proved two limits of live verification. EDT XML export combined model metadata with BSL read from disk, and 1C applied a dynamic database update during a running client session. The same stand confirmed the runtime distinguishes two builds of the existing `TestConfiguration`. Changing `Calc.Add` from returning 5 to returning 12 changed the actual value in the JUnit report.

## Goals / Non-Goals

- Start each bound run from one immutable disk capture and build it in its own workspace and file infobase.
- Keep the normal YAxUnit job lifecycle, selectors, Markdown reports, and snapshot access checks.
- Cover directly attached configuration extensions on the dedicated Windows stand. Live data, live application settings, and debug launches are out of scope.

## Decisions

1. **Capture the build inputs explicitly.** Add `forYaxunit` to snapshot capture. The guarded Windows reader retains all files under `src/`, subject to the source grant and size limits, plus the settings required to identify an EDT project. Reject files outside the supported input set, linked or virtual resources, unsupported project descriptors, missing dependencies and inconsistent reads. Validate project names, configuration or extension type, extension base project, and runtime version. Count payload bytes against snapshot quotas. Only retained inputs are available to the execution worker. The build-readiness flag is separate from the live EDT model observation.

2. **Dispatch an isolated job.** Resolve `snapshotId` through the authenticated route and require explicit execution grants on the captured projects. No caller-controlled file path or executable is accepted. Preserve the lease through materialization and check current grants before launching and exposing evidence. Each submission owns a separate job and directory. Exceeding execution capacity returns a retryable error.

3. **Use the platform tools in a private directory.** Materialize the validated inputs into a server-owned per-job directory. Import and export them with `1cedtcli` in an empty workspace, with the base project before its extensions. Create the job's file infobase from a pinned, dedicated engine-only template, then apply the exported configuration and extensions with Designer. Start the configured thin client with the explicit path to the temporary infobase and fresh YAxUnit parameters. The operator configures absolute executable locations, a local job directory, and the engine template digest. Preserve the operator's trusted execution boundary around project code. The temporary directory isolates the run inputs and database and carries no live infobase data.

4. **Require a fresh report and clean up.** Reuse the existing selectors, JUnit parser, formatter and job polling. Require at least one executed test and attach retained input fingerprints, selectors and snapshot identity to the returned result. Use the authenticated owner and live grants to authorize status and cancellation, and redact bound arguments and results in request history and HTTP tracing. Bound process lifetime, output and workspace use. Stop the job's process tree before removing its private directory and handle restart leftovers within the configured root.

5. **Validate the fixture on the dedicated stand.** Use the existing `TestConfiguration` and `tests` projects, the installed platform, and the pinned YAxUnit engine. Keep the stand's Russian language dependency. Capture before and after changing `Calc.Add`, run both snapshots, and confirm the older capture still passes and the newer one reports the changed value. Check the existing failure demo and restore the disposable source fixture.

## Risks / Trade-offs

- Fresh EDT workspace initialization can exceed the MCP start-call timeout. Report the background job ID and keep preparation cancellable.
- Export can return a partial result. Require successful platform commands and complete configuration outputs and propagate an actionable phase error when the outputs cannot be applied.
- A temporary infobase has no application data except what the empty engine template and the tests produce. Tests that require additional data must seed it independently.

## Migration Plan

Enable the execution grants and configure the toolchain on the dedicated Windows test server. Run the existing legacy baseline and the isolated acceptance cases. A server without the toolchain can continue to serve inspection and ordinary YAxUnit launches.
