Deletes an EDT project application — a project-to-infobase binding — so it stops appearing in `get_applications` for the project. The inverse of `create_project_application`.

The tool does NOT remove the infobase itself: because only the association is deleted (via EDT's public `IApplicationManager.delete(app, unsynchronize=true)` — the same API EDT's GUI uses), the underlying base stays registered in `list_infobases` and its files stay on disk. That is what distinguishes this tool from `delete_infobase`, which removes the base registration and can also delete its database files.

## Think twice — destructive (confirm-preview)

Deleting an application removes its binding from the project (a standalone-server application also has its server/runtime configuration cleaned up by EDT's own deletion flow). The base is untouched and the application is re-creatable (`create_project_application` / `set_branch_infobase`), but a client may still want to confirm first — so the tool is guarded by a two-phase workflow mirroring `delete_infobase`:

1. **Preview** (`confirm` omitted / false, the default): resolves the application and returns `action='preview'`, `confirmationRequired=true`, the target identifiers and a message stating the infobase is NOT removed — WITHOUT changing anything.
2. **Delete** (`confirm=true`): performs the deletion via `IApplicationManager.delete`, then reports `action='deleted'` together with a read-back verdict.

## Parameter details

- **projectName** (required): the EDT configuration project whose application to delete.
- **applicationId** (required): application id from `get_applications`.
- **confirm** (boolean, default false): false previews; true performs the deletion.

## Result

JSON with `action` ('preview'/'deleted'), `confirmationRequired` (preview only), `project`, `applicationId`, `applicationName`, `removed` (whether a read-back confirmed the application is gone from `get_applications`), `remainingApplications` (evidence, after a delete) and a `message`.

## Typical usage

```
# 1. Preview what would be removed (nothing changes).
delete_project_application  projectName="mom"  applicationId="ServerApplication.Инвест"

# 2. Confirm removal (keeps the infobase registered).
delete_project_application  projectName="mom"  applicationId="ServerApplication.Инвест"  confirm=true
```

## Relationship to the other application tools

| Goal | Tool |
| --- | --- |
| List the project's applications | `get_applications` |
| Create a NEW application for a branch over an existing base | `create_project_application` |
| Re-bind an existing application to a branch context (attach/detach) | `set_branch_infobase` |
| Delete an application WITHOUT touching the base | `delete_project_application` (this tool) |
| Delete the base registration (and optionally its files) | `delete_infobase` |

## Gotchas

- **The base is NOT removed**: after `delete_project_application`, the infobase still shows in `list_infobases`. To also deregister/remove the base, follow up with `delete_infobase` (which supports `deleteDatabaseFiles`).
- **`removed=false` is not silent failure**: the deletion call raised no error but a read-back still shows the application. This usually means the type delegate could not finish within the read-back budget — most often a **running standalone server** that must be stopped first (`terminate_launch`, or the Servers view) — or an async removal still in flight. Check `get_applications` and, for a standalone application, ensure its launch is stopped before retrying.
- **Applies to any application type** EDT can delete (infobase, standalone server, ...); unlike `delete_infobase` it does not special-case server registry cleanup.
