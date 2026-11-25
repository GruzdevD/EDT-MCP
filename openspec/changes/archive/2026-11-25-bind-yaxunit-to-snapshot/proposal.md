# Proposal

## Why

An agent can review a retained EDT source snapshot, but an ordinary YAxUnit launch can use a different source version or an infobase updated by another process. A snapshot-bound run needs its own build inputs and infobase so the report identifies the source version that actually ran.

## What Changes

- Add an opt-in build payload to source snapshot capture on the authenticated Windows route. Retain the complete authorized disk inputs for the base configuration and the explicitly selected configuration extensions.
- Add `snapshotId` to report-producing `run_yaxunit_tests`. Build from the retained inputs in a private workspace and use a separate temporary file infobase for each run. A retained snapshot remains runnable after changes to the working tree.
- Return source fingerprints and invocation-specific JUnit results. Restrict access to the same authorized owner, and clean up the temporary workspace and database after the run.
- Use the repository's lightweight `TestConfiguration` and its existing `tests` extension for Windows acceptance. The dedicated test stand uses the installed 1C 8.5.1 platform and YAxUnit engine.
- Preserve ordinary YAxUnit launches and snapshot inspection. Bound runs use local, server-configured tools and a pinned engine-only template. Debugging, copying data from a live infobase, and Git commits are outside this change.

## Capabilities

### New Capabilities

### Modified Capabilities

- `project-source-snapshots` gains an opt-in build payload and evidence from YAxUnit runs in temporary infobases.

## Impact

The change affects Windows snapshot capture and retention, the YAxUnit background-job path, protected job access, tool guides, and live fixture tests. The Windows server requires the EDT command-line exporter, 1C Designer and thin client, and a dedicated YAxUnit template.
