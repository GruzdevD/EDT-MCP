Provisions one project's Vanessa Automation turnkey: creates the `.vanessa/` layout in the EDT project root (`features/`, `out/`, `env.sh`, `VAParams.json` from bundled templates), and — if the `vanessa-automation.epf` runtime is missing — starts a background job that downloads it (plus `locales/`) from the pinned GitHub release into the shared cache `~/.1c-tools/vanessa/va/<version>/`, then points `EPF` in `env.sh` at it. Optionally also installs the Allure CLI (`installAllure: true`). Existing edited files are never overwritten; rerunning is safe and idempotent.

This is the explicit, harness-facing provisioning interface. The same layout is also created automatically for all EDT projects at plugin activation, and the epf is downloaded lazily on the first `vanessa_run_feature` / `vanessa_run_by_tags` — so on a fresh install you usually only need `vanessa_setup` (or `vanessa_doctor` to check readiness).

## When to use

- First-time setup of a project for VA.
- To force (or verify) the epf runtime install before running scenarios.
- The harness-facing entry point called from scripts/opencode instead of relying on the lazy trigger.

## Parameter details

- `project` (required) — the project key to provision, e.g. `afm`. Creates/updates the EDT project's `.vanessa/` (falls back to `~/.1c-tools/vanessa/projects/<project>/` when the workspace is unavailable).
- `installAllure` (optional, default `false`) — also install the Allure CLI under `~/.1c-tools/allure` so `vanessa_open_allure_report` works.

When everything is already in place and `installAllure` is false, the tool returns immediately with a `ready` status and downloads nothing.

## Example

```
vanessa_setup(project: "afm", installAllure: true)
```

Returns a Markdown report. If a background download was started (or was already in flight), it includes the `jobId` — poll it with `get_job_status`.

## Errors

- `project is required.` — no project key.
- `Failed to provision ...` — a file write or the layout creation failed.
- Download failures return an actionable message naming the expected cache path and how to satisfy it manually.
