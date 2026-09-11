Deploy the newest published build of this plugin over git. It fetches the `update-site` branch,
installs the new `com.ditrix.edt.mcp.server_<version>.jar` into `~/.p2/pool/plugins/` and rewrites
`bundles.info`. Returns the new version and the installed jar path; the change takes effect after an
EDT restart.

## When to use

- To apply the newest published plugin build in place, the way the in-EDT update flow does.
- After `plugin_check_for_update` reported `updateAvailable: true`.

## What it does

| Step | Effect |
|---|---|
| fetch `update-site` branch | locate the newest published `com.ditrix.edt.mcp.server_*.jar` |
| install into `~/.p2/pool/plugins/` | place the jar alongside the installed bundles |
| rewrite `bundles.info` | point the plugin's line at the new jar/version |

## Notes

- Requires an EDT restart for the new build to take effect; the run is reported via
  `get_job_status`.
- Uses the same git credential resolution as `plugin_check_for_update`, so a private repository
  works unchanged.
- Run `plugin_check_for_update` first to confirm an update is actually available.
