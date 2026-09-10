Recursively lists the Vanessa Automation `.feature` files available under a project's feature directory (or an explicit `dir`), each with its `Feature:` headline and feature-level tags, plus a `count`. Pure file work on the operator's out-of-repo feature dir `~/.1c-tools/vanessa/features/<project>`.

## When to use

Before running or inspecting scenarios: get the inventory of what a project has, then drill into a file with `vanessa_get_feature` or run it with `vanessa_run_feature`. Also useful to discover the tag vocabulary so you can target `vanessa_run_by_tags`.

## Parameter details

- `project` (required) — the project key whose `FEATURES_DIR` to scan, e.g. `afm` (see `~/.1c-tools/vanessa/projects/<project>/env.sh`).
- `dir` (optional) — an absolute directory to scan instead of the project's `FEATURES_DIR`.

## Examples

List everything for a project:

```
vanessa_list_features(project: "afm")
```

Scan a specific directory:

```
vanessa_list_features(project: "afm", dir: "/home/user/features/integration")
```

Returns `{root, count, features: [{path, featureName, tags}]}`. Each `path` can be passed to `vanessa_get_feature` or `vanessa_run_feature`.

## Errors

- `project is required.` — no project key.
- No config — the project `env.sh` is missing (see the `notFoundMessage`).
- `Failed to scan feature dir ...` — the directory does not exist or is unreadable.
