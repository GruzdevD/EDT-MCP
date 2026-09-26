Toggles a project's EDT MASSIVE CHECK PROCESS — the "расширенные проверки и валидации" that run around infobase synchronization/update and are the usual reason an `update_database` is slow or even blocked. Disable it before an update, re-enable it after.

This is the per-project preference `disableMassiveChecks` (node `com.e1c.g5.v8.dt.check`), toggled through the `ICheckRepository` service — exactly the path EDT's own project property page («Настройки процесса проверок») uses, so the change persists and takes effect immediately (no restart). It is reversible: `disableMassiveChecks=false` (or the EDT default) restores normal checking.

## Parameter details

- **projectName** (required): the EDT project whose massive-check process to toggle.
- **disableMassiveChecks** (required, boolean): `true` disables the massive check process (fast updates); `false` re-enables it (full checks). Recognised value spellings: `true/1/yes`, `false/0/no`.

## Result

JSON with `success`, `project`, `disableMassiveChecks` (read-back of the flag after the change), `changed` (whether this call actually changed it — `false` means it already had the value) and `message`.

## Typical usage

```
# 1. Disable the massive checks before running a slow update.
set_project_checks  projectName="mom"  disableMassiveChecks=true

# 2. Run the update (now fast), then restore full checks.
update_database  ...
set_project_checks  projectName="mom"  disableMassiveChecks=false
```

## Relationship to other tools

| Goal | Tool |
| --- | --- |
| Toggle the massive check process around an update | `set_project_checks` (this tool) |
| Synchronize/update the configuration into the infobase | `update_database` |
| List the project's applications | `get_applications` |

## Gotchas

- **Re-enable after the update** — disabling the checks permanently hides real configuration validation problems; treat it as a temporary speed-up around `update_database`, then restore.
- **Scope is the file/server project-scoped preference only.** EDT's separate critical data-integrity markers gate in the Deploy Configuration wizard is NOT this preference and is unaffected.
- **Read-back honesty**: `disableMassiveChecks` in the result is the flag as read back after the write; if the read-back could not confirm the new state, `message` says so. `changed=false` is not a failure — it means the project already had the requested value.
