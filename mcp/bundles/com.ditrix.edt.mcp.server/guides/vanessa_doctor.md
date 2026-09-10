Read-only readiness report for one project's Vanessa Automation setup. It checks — against the project's filesystem and the shared runtime cache, without touching the network — whether the pieces VA needs are in place: the `.vanessa/` layout in the EDT project root, `env.sh`, `VAParams.json`, the `vanessa-automation.epf` runtime, the Allure CLI (only for `vanessa_open_allure_report`), and a resolvable 1C/EDT launch config. Returns the state as a Markdown checklist plus the exact next step when something is missing.

## When to use

- Before the first `vanessa_run_feature` / `vanessa_run_by_tags` on a fresh project, to see what is still missing.
- After running `vanessa_setup` (or letting the install-time layout job run) to confirm readiness.
- To distinguish "VA isn't provisioned" from a genuine run failure when a run misbehaves.

## Parameter details

- `project` (required) — the project key whose `.vanessa/` layout and/or legacy `~/.1c-tools/vanessa/projects/<project>/env.sh` is checked, e.g. `afm`.

## What Doctor checks

| Check | Meaning |
|---|---|
| layout | `.vanessa/{features,out}` present in the EDT project root |
| env.sh | project `env.sh` (`.vanessa/env.sh`, falling back to `~/.1c-tools/vanessa/projects/<project>/env.sh`) exists |
| vaparams | `VAParams.json` exists next to `env.sh` |
| epf | `vanessa-automation.epf` exists (path from `EPF` in `env.sh`, or the shared cache) |
| allure | Allure CLI present under `~/.1c-tools/allure` (needed only for `vanessa_open_allure_report`) |
| 1C/launch | the EDT launch configuration named by `EDT_LAUNCH` exists |

A run is ready when layout, `env.sh`, `VAParams.json` and `epf` are all present; Allure is only required if you plan to generate Allure reports.

## Example

```
vanessa_doctor(project: "afm")
```

Returns a Markdown report: `**Ready:**` (checked list) or `**Not ready.**` with a per-row table and the next step — usually "run `vanessa_setup(project: "afm")`".

## Errors

- `project is required.` — no project key.
