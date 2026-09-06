Reads and lightly parses a single Vanessa Automation `.feature` file: the `Feature:` headline, feature-level tags, each scenario's name/tags/steps, and the raw source. Pure file work — no EDT model access.

## When to use

To understand what a feature (from `vanessa_list_features`) actually contains before running it, or to check scenario names/tags before targeting `vanessa_run_feature` (scenarios filter) or `vanessa_run_by_tags`.

## Parameter details

- `path` (required) — the absolute path to the `.feature` file, as returned by `vanessa_list_features`.

## Example

```
vanessa_get_feature(path: "/home/user/.1c-tools/vanessa/features/afm/core/005_Создание_контрагента_UI.feature")
```

Returns `{path, feature, tags, scenarios: [{name, tags, steps}], raw}`. `raw` is the unmodified file source. The parse is intentionally lightweight and line-oriented; use `raw` when exact gherkin fidelity matters.

## Errors

- `path is required.` — no path.
- `No such feature file: ...` — the path does not exist.
- `Failed to read feature file ...` — unreadable file.
