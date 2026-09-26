Lists the infobases registered in EDT's GLOBAL infobase panel (the "Инфобазы" / "Infobases" view) — the file and client-server databases EDT knows of across all projects, independent of any one project's applications.

The panel is a tree: root sections are either folders (groups) or infobase leaves. This tool walks the whole tree depth-first and reports every leaf with its folder path, connection string and type. This is the registry a caller reads to pick an EXISTING database by name — e.g. to point a new project application at it via `create_project_application`.

## Parameter details

- **groupName** (optional): an EDT infobase-panel folder name to restrict the listing to. Leave empty to list every registered infobase. The match is case-insensitive.

## Result

JSON with `success`, `infobases` (array), `groups` (folder summary), `recent` (names of EDT's recent infobases) and `total`.

Each entry in `infobases`:
- **name** — the infobase name (use this with `create_project_application`).
- **uuid** — EDT's stable id for the base.
- **type** — `FILE` or `SERVER`.
- **connectionString** — the connection string.
- **file** — for a FILE base, the .1cv8/1Cv8.1CD path on disk.
- **folder** — the panel folder it lives in.
- **external**, **recent** — markers.

Each entry in `groups`: `{name, count}`.

A `groupName` that matches no folder returns an error naming the filter and steering you to call without `groupName` — so an empty result is never silently read as "no infobases".

## Typical usage

```
# List every registered infobase, grouped by folder.
list_infobases

# Narrow to a single folder.
list_infobases  groupName="Production"
```

## Relationship to the other base/application tools

| Goal | Tool |
| --- | --- |
| List the registered infobases (to name one) | `list_infobases` (this tool) |
| List the project's applications | `get_applications` |
| Create a DISTINCT application for a branch over an existing base | `create_project_application` |
| Register/create a NEW infobase | `create_infobase` |
| Delete an application WITHOUT touching the base | `delete_project_application` |

## Gotchas

- **This is the global registry, not a project view**: an infobase listed here may not be bound to any project's application. Use `get_applications` for the per-project view.
- **Reads the in-memory registry only** — it opens no infobase connection, so it is safe to call anytime (unlike connection-reaching tools).
- **Folder names are presented as-is** by EDT; the `groupName` filter is compared case-insensitively so you do not need to reproduce exact case.
