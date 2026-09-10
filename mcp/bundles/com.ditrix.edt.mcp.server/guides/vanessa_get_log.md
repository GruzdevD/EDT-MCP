Reads lines from a Vanessa Automation run log (`BDD.log`). The log is addressed by `launchId` (reusing the run's recorded out dir) or by an absolute `logPath`. Big logs are paged with `offsetLines`/`limit`; the response carries offsets and a `more` flag so the client knows whether to fetch the next page.

## When to use

When `vanessa_get_execution_status` says a run finished but you need the raw runtime log to understand a failure, or to tail a long-running run's output. For a structured per-test summary use `vanessa_get_test_report` instead.

## Parameter details

- `launchId` (optional) or `logPath` (optional) — exactly one is required. `launchId` reads `<outDir>/BDD.log`; `logPath` is an absolute path to any log.
- `offsetLines` (optional, default `0`) — zero-based line offset to start from.
- `limit` (optional, default `200`, capped at `2000`) — max lines to return.

## Example

First page, then the next 100 lines:

```
vanessa_get_log(launchId: 42)
vanessa_get_log(launchId: 42, offsetLines: 200, limit: 100)
```

Returns `{path, totalLines, offset, returned, more, lines}`. Page forward by setting `offsetLines` to the previous `offset + returned` while `more` is true.

## Errors

- `Either logPath or a numeric launchId is required.` — neither selector.
- `Unknown launchId ...` — no such run handle.
- `No log file at ...` — the log does not exist (run may still be starting, or the log is written elsewhere).
