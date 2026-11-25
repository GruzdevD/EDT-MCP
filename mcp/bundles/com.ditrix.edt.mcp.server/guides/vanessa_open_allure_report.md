Generates and opens a report from the raw Allure results of a Vanessa Automation run. The report is served over loopback on the EDT host.

## Parameters

- `launchId` — ID from `vanessa_run_feature` or `vanessa_run_by_tags`.
- `outDir` — Absolute output directory of the run. Provide this or `launchId`. If both are supplied, `outDir` takes precedence.
- `resultsDir`, `reportDir` — Optional overrides for the raw Allure results and the generated HTML report directories.
- `allureBin` — Optional Allure CLI path. The default lookup uses the project configuration, the local Allure installation, and the process PATH.
- `generate` defaults to true. Set it to false to reuse a report with an existing `index.html`. The raw results directory still needs to be available.
- `detached` defaults to false, which opens the EDT Allure Report view. Set it to true to open the operating system browser.

## Examples

```text
vanessa_open_allure_report(launchId: 42)
vanessa_open_allure_report(launchId: 42, generate: false, detached: true)
```

The result includes the report and results directories, local URL, port, and display mode. Enable Allure output for the run if the raw results are missing.
