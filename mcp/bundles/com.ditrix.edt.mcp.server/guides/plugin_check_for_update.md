Check whether a newer build of this plugin is published on its git repo. It fetches the
`update-site` branch (which holds the built `com.ditrix.edt.mcp.server_<version>.jar`) and compares
it with the version running in the current EDT. Returns the installed version, the available
version and whether an update is available. Authenticates with the project git credentials, so a
private repository stays private.

## When to use

- To see if a newer plugin build has been published before deciding to update.
- In automation/CI to detect that an upgrade is pending.
- As the read-only counterpart to `plugin_update` — nothing is changed here.

## What it returns

| Field | Meaning |
|---|---|
| installedVersion | the bundle version currently running in EDT |
| availableVersion | the highest `com.ditrix.edt.mcp.server_*.jar` version on the `update-site` branch |
| updateAvailable | whether the published build is newer than the installed one |

## Notes

- Uses a fresh clone of the configured repository under `~/.1c-tools`, resolving git credentials
  (e.g. from the local git config) the same way the plugin's other git operations do.
- Runs in the background; poll `get_job_status` for the result.
