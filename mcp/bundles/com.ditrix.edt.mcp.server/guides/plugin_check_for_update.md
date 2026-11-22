Checks the plugin's Git artifact branch for a published build and compares it with the version running in EDT. It fetches the branch into the local update cache.

## Parameters

- `repositoryUrl` (optional) — Git remote that publishes plugin builds. Defaults to the plugin's project repository. A private remote requires Git credentials configured for the EDT process.
- `branch` (optional) — Artifact branch containing the versioned bundle under `plugins/`. Defaults to `update-site`.

## Example

```text
plugin_check_for_update()
```

Read `installedVersion`, `availableVersion`, and `updateAvailable`. If the running version is unavailable, `installedVersion` is `unknown` and `updateAvailable` is true. To install an update, call `plugin_update` with the same source.
