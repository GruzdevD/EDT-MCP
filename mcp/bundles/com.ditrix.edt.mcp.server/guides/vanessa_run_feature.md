Launches a Vanessa Automation BDD run on a project, mirroring the project's `run-edt.sh` driver: it reads the operator's out-of-repo `~/.1c-tools/vanessa/projects/<project>/env.sh` (infobase, binary, VAParams, launch config, VA_Runner), generates the VAParams override and junit/Allure run artifacts VA consumes, starts `VA_Runner` on the project's EDT launch configuration, and registers a run handle. Returns a `launchId` for `vanessa_get_execution_status` (poll until it leaves `running`) and `vanessa_get_test_report`.

## When to use

The entry point for running one feature or scenario on a project. For a tagged slice of the suite use `vanessa_run_by_tags` instead.

## Parameter details

- `project` (required) — the project key whose `env.sh` drives the run, e.g. `afm`.
- `feature` (optional) — absolute path to a feature file or directory; defaults to the project's `FEATURES_DIR`.
- `launchConfigurationName` (optional) — the EDT launch configuration that starts the 1C client; defaults to the project's `EDT_LAUNCH`.

Per-run overrides (all optional; absent ones leave the base VAParams untouched):

- `tagsFilter` / `tagsIgnore` — run only / skip scenarios or features carrying these tags (VAParams `СписокТеговОтбор` / `СписокТеговИсключение`).
- `scenarios` — run only these scenario names, comma-separated (VAParams `СписокСценариевДляВыполнения`).
- `retries` — retry count for a failed scenario (`>=1`; `2` = one retry), VAParams `КоличествоПопытокВыполненияСценария`.
- `screenshotsOnError` — take a screenshot when a scenario step fails (VAParams `ДелатьСкриншотПриВозникновенииОшибки`).
- `asyncSteps` — run steps asynchronously / slowly (VAParams `ВыполнятьШагиАсинхронно`).
- `allure` — generate an Allure HTML report next to the junit one (VAParams `ДелатьОтчетВФорматеАллюр`).
- `reportDir` — absolute out dir for logs/junit/allure instead of the project default `~/.1c-tools/vanessa/out/<project>`.

## Example

```
vanessa_run_feature(project: "afm", feature: "/home/user/.1c-tools/vanessa/features/afm/core/005_Создание_контрагента_UI.feature", tagsFilter: "smoke", screenshotsOnError: true)
```

Returns `{launchId, project, feature, configuration, status, outDir, junitReportPath, options}` — `options` echoes back the applied overrides for transparency.

## Errors

- `project is required.` — no project key.
- No config — the project `env.sh` is missing.
- `Failed to generate Vanessa run artifacts: ...` — a VAParams read or artifact write failed.
- Launch failures return the underlying `LaunchTool` error JSON; the run handle is still registered so `vanessa_get_execution_status` can report the failure.
