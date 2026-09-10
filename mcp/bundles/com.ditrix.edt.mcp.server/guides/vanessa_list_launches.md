Lists the EDT launch configurations usable for a Vanessa Automation BDD run, optionally filtered to a project. Each entry carries the configuration `name`, its `project` and a boolean `vanessa` hint (the name looks like a BDD/vanessa config). The caller typically finds a matching configuration here, then passes its name to `vanessa_run_feature` / `vanessa_run_by_tags` (or the project's `EDT_LAUNCH` from `env.sh` is used by default).

## When to use

Before the first BDD run, or to pick an alternate launch configuration. Most runs can skip this and rely on the project's `EDT_LAUNCH` default.

## Parameter details

- `projectFilter` (optional) — only list configurations whose project (or name) contains this substring.

## Example

```
vanessa_list_launches(projectFilter: "afm")
vanessa_list_launches()
```

Returns `{count, launches: [{name, project, vanessa}]}`.

## Errors

- `Debug plugin is not available; EDT is still starting up.` — the debug core is not ready yet.
- `Failed to list launch configurations: ...` — the debug manager raised an error.
