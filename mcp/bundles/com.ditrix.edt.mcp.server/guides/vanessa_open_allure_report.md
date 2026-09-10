Generate and open the Allure report of a Vanessa Automation BDD run in the in-EDT Allure Report
view (or the OS browser when detached). Address the run by its launch id from
`vanessa_run_feature` / `vanessa_run_by_tags`, or by an absolute `outDir`. Generates the static
report from the run's raw Allure results via the `allure` commandline, serves it over loopback and
opens it.

## When to use

- After a `vanessa_run_feature` / `vanessa_run_by_tags` run finishes, to review its Allure results
  visually.
- To re-open the report of a completed run by its out-dir without re-running anything.
- In combination with `vanessa_get_artifacts` / `vanessa_get_test_report` for a fuller reading.

## What it returns

| Field | Meaning |
|---|---|
| reportPath | where the generated static report was written |
| url | the loopback URL being served (empty when `detached`) |
| viewOpened | whether the report was opened in the in-EDT Allure Report view |

## Notes

- Needs the `allure` commandline: the built-in rule checks for it under `~/.1c-tools/allure` (see
  `vanessa_doctor`). Point a custom binary via `allureBin`.
- Runs in the background; poll `get_job_status` for the result.
