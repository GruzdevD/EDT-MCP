# Spec Delta

## ADDED Requirements

### Requirement: Retained build inputs for YAxUnit
`capture_project_snapshot` SHALL accept `forYaxunit: true` to retain the complete build inputs from the authorized disk scope. The response SHALL identify whether the snapshot can be built for YAxUnit. Capture MUST refuse an incomplete or unstable payload and MUST leave sources and editors untouched. The live EDT model status SHALL retain its inspection semantics.

#### Scenario: A complete disk snapshot has unknown model status
- **WHEN** a stable capture records all authorized build inputs, including unchanged sources and project descriptors, while the live model status is unknown
- **THEN** the snapshot is eligible for an isolated build and remains marked unknown for live-model inspection.

#### Scenario: A build input cannot be captured safely
- **WHEN** a required project descriptor or source is missing, linked, outside the allowed source types, or exceeds a limit
- **THEN** the build capture is refused and no runnable snapshot is issued.

### Requirement: Admission of a snapshot-bound YAxUnit run
`run_yaxunit_tests` SHALL accept `snapshotId` for report-producing Windows runs. The request MUST use the authenticated route, a retained build payload owned by the caller, and current execution grants for every selected project. The snapshot defines the base and extension projects. Omitting `snapshotId` SHALL preserve ordinary launch behavior.

#### Scenario: A live application or debug mode is requested
- **WHEN** the caller supplies a live application, launch configuration, debug mode, or live update options with `snapshotId`
- **THEN** the server rejects the combination before creating a workspace, opening an infobase, or terminating a client.

#### Scenario: The snapshot is unavailable to the caller
- **WHEN** an inspection-only, expired, or foreign snapshot is requested, or a project grant has been revoked
- **THEN** the server refuses the run without exposing another owner's snapshot.

### Requirement: Build and run in a temporary infobase
A bound job MUST create its own temporary workspace and file infobase using only the retained source payload and the pinned engine template. It MUST apply the captured configuration and extensions before starting a fresh thin client. It MUST release temporary resources on completion, failure, and cancellation.

#### Scenario: The working tree changes after capture
- **WHEN** a source file is edited, deleted, or renamed after the snapshot is captured
- **THEN** a bound run still consumes the retained version and does not read the changed source from the working tree.

#### Scenario: A dependency cannot be built
- **WHEN** an extension has an unsupported or missing dependency, an artifact fails to load, or the engine template fails its identity check
- **THEN** the server stops and reports the failed phase without running against a live infobase.

#### Scenario: The same snapshot is submitted twice
- **WHEN** the caller starts another bound job from the same snapshot
- **THEN** the new job uses a separate temporary infobase, client, and JUnit report.

### Requirement: Snapshot evidence in the YAxUnit report
The server SHALL associate the job ID, `snapshotId`, project input fingerprints, and effective test selectors with a fresh JUnit report that contains at least one executed test. Immediate and polled results MUST carry the same evidence. Access to bound job results MUST follow the snapshot ownership and grant rules.

#### Scenario: A genuine assertion fails
- **WHEN** a fresh report from the isolated run contains an assertion failure
- **THEN** the result preserves the failure and the snapshot evidence.

#### Scenario: No valid execution report exists
- **WHEN** the job is cancelled, starts no tests, or has no valid fresh JUnit report
- **THEN** the result identifies the outcome without claiming verified execution.

#### Scenario: A different caller polls the job
- **WHEN** a caller without current access presents a bound job ID
- **THEN** the server withholds the report and snapshot metadata.
