Lists the files a Vanessa Automation run produced in its out dir — the junit report, Allure artifacts, screenshots and the run log — each with a byte size and a coarse `kind` classification (`junit`, `allure`, `screenshot`, `log`, `data`, `other`). The dir is addressed by `launchId` (reusing the run's recorded out dir) or by an absolute `outDir`. Pure file work on the run artifacts.

## When to use

To collect what a run generated: find the junit report, locate screenshots of a failing step, or enumerate Allure files for a report link — after (or while) polling `vanessa_get_execution_status`.

## Parameter details

- `launchId` (optional) or `outDir` (optional) — exactly one is required. `launchId` scans the run's out dir; `outDir` is an absolute path to scan.
- Where do screenshots live? Vanessa writes `[[ATTACHMENT|...]]` links into the junit `<system-out>` (visible via `vanessa_get_test_report` with `detail: "steps"`); the screenshot files themselves typically land in the Allure output dir, classified here as `allure`/`screenshot`.

## Example

```
vanessa_get_artifacts(launchId: 42)
```

Returns `{outDir, count, artifacts: [{path, size, kind}]}`.

## Errors

- `Either outDir or a numeric launchId is required.` — neither selector.
- `Unknown launchId ...` — no such run handle.
- `No such out dir: ...` — the directory does not exist.
