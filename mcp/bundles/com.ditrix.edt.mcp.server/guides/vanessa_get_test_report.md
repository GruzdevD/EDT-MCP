Parses the Vanessa Automation junit.xml report into a structured, client-friendly summary: per-suite counts, per-test status (passed/failed/error/skipped) and an overall verdict. The report path is supplied directly as `junitReportPath`, or reused from a previously launched run referenced by `launchId`.

## When to use

After `vanessa_get_execution_status` reports `passed`/`failed`, to get the actual per-test breakdown. For the raw runtime log use `vanessa_get_log`; to enumerate screenshot/report artifacts use `vanessa_get_artifacts`.

## Parameter details

- `junitReportPath` (optional) — absolute path to the junit.xml. Either this or `launchId` is required.
- `launchId` (optional) — a run id whose recorded `junitReportPath` is used.
- `detail` (optional, default `tests`) — how much per-test detail to include:
  - `summary` — counts and overall verdict only (no test cases).
  - `tests` — per-test `status` (+ a short `message` preview on failure/error).
  - `steps` — everything in `tests` plus each test's `attachments` (the `[[ATTACHMENT|...]]` screenshot links from `<system-out>`) and the full `detail` failure/error text.

## Examples

```
vanessa_get_test_report(launchId: 42)
vanessa_get_test_report(junitReportPath: "/home/user/.1c-tools/vanessa/out/afm/junit/junit.xml", detail: "steps")
```

Returns `{report: {path, suites, testCount, failureCount, errorCount, skippedCount, passedCount, verdict}}`.

## Errors

- `Either junitReportPath or launchId is required.` — neither selector.
- `Invalid launchId: ...` / `Unknown launchId ...` — bad or unknown run id.
- `Failed to parse JUnit report '...' : ...` — missing, unreadable or malformed report.
