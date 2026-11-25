Installs the newest published plugin bundle from its Git artifact branch. It replaces old copies in the user's p2 plugin pool and updates the running EDT installation's `bundles.info` when that file is available. Restart EDT to load the new bundle.

## Parameters

- `repositoryUrl` (optional) — Trusted Git remote that publishes the plugin. Defaults to the plugin's project repository.
- `branch` (optional) — Artifact branch containing the versioned bundle under `plugins/`. Defaults to `update-site`.

## Example

```text
plugin_update()
```

Run `plugin_check_for_update` first with the same source. After installation, read `newVersion`, `target`, and `bundlesInfo`. The last field reports whether the bundle registry was updated. `restartRequired` is true after the bundle is installed.
