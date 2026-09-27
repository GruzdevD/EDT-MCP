Creates a Specter UI-testing launch configuration that references a base EDT runtime-client launch configuration. Requires the Specter EDT plugin (`ru.ozon.uitp.e2e`) to be installed in this EDT.

## How Specter launch configurations work

Specter launches UI tests by cloning a base `RuntimeClient` configuration and stamping the bridge startup option (`SPECTER_START_BRIDGE|outDir=...`), the infobase auto-login, and the automated-testing (TESTMANAGER) mode onto the clone. The Specter configuration itself is tiny: it only stores

- **baseLaunchConfig** — the name of the base runtime-client configuration (application, infobase and user are inherited from it);
- **testingPort** — the TESTMANAGER port for the channel-B testing mode (0 disables the mode).

At launch time the plugin's delegate resolves the base configuration and prepares the single reusable clone `Specter: <base>` — it never multiplies launch configurations. Run and debug share the same config type, like `create_launch_config`.

## Parameter details

- **baseLaunchConfig** (required): an existing `RuntimeClient` (`com._1c.g5.v8.dt.launching.core.RuntimeClient`) configuration name. Other types (attach/standalone-server) are rejected — the base must launch a 1C client. Use `list_configurations` to see candidates.
- **name** (optional): exact name for the new Specter config; default `Specter UI-тесты` (uniquified). A duplicate name is rejected.
- **testingPort** (optional, default `4811`): the TESTMANAGER port; the clone is launched with the platform's automated-testing mode on this port. Pass `0` to disable the mode (ordinary client run, channel A only).

## Result

JSON with `action='created'`, `name`, `baseLaunchConfig`, `testingPort`, `type` (`ru.ozon.uitp.e2e.launcher.specter`), and a `message` with next-step hints.

## Example workflow

```
1. list_configurations({})
   -> find a runtime-client config, e.g. "Новая_конфигурация"

2. create_specter_launch_config({baseLaunchConfig: "Новая_конфигурация", testingPort: 4811})
   -> {"action": "created", "name": "Specter UI-тесты", ...}

3. Run the tests from the Specter plugin UI ("Тесты расширения (СП)" -> Run)
   or launch the config by name from Eclipse Run Configurations.
```

## Gotchas

- The Specter plugin must be installed; otherwise the tool reports the unregistered type and stops.
- The base configuration must exist at launch time too — if it is deleted later, the Specter launch fails with an actionable error naming the missing base.
- Launch configurations live in workspace `.metadata` (not in project files), so they do not appear in git diffs. Use `delete_launch_config` for cleanup.
- The TESTMANAGER mode requires a free port; a leftover TestClient holding the port makes the run fail — terminate it first.
