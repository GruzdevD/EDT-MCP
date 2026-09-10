Polls the execution status of a Vanessa Automation BDD run previously started by `vanessa_run_feature` / `vanessa_run_by_tags`. The terminal state is read from the artifacts VA writes into the project out dir — a numeric `BDDStatus.log` means the run finished (`0` = passed, otherwise failed) — the same way the project's `run-edt.sh` polls. Answers "is it done yet, and did it pass".

## When to use

In a poll loop after launching a run, until the returned `status` leaves `running`. Once finished, read the details with `vanessa_get_test_report`.

## Parameter details

- `launchId` (required) — the id returned by `vanessa_run_feature`.

## Example

```
vanessa_get_execution_status(launchId: 42)
```

Returns `{launchId, project, feature, status}` where `status` is `running` | `passed` | `failed`. Once not `running` it also includes `junitReportPath`; on a rejected launch it includes an `error` field.

## Errors

- `launchId must be a whole number, got: ...` — invalid id.
- `Unknown launchId 42.` — no such run handle (handles live only while EDT is up).
