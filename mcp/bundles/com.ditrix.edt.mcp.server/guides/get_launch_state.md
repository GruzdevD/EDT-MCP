# get_launch_state

Inspect live EDT launches and OS processes. Parameters and examples: get_tool_guide('get_launch_state').

## Parameters
| Parameter | Required | Type | Description |
| --- | --- | --- | --- |
| projectName | — | string | Filter by launch-configuration project name. |
| includeTerminated | — | boolean | Include terminated launches and their exit codes (default true). |
| tailLines | — | integer | Console tail lines per process (default 40, max 200). |

## Guide
Snapshot of the launch state: live launches (configuration, project, state, PID), raw OS processes belonging to EDT clients, modal windows detected on client processes, and diagnostics (live launch count, orphaned OS processes without a launch record, processes with non-zero exit).

Use it to answer "is anything actually running?" before killing clients or restarting EDT, and to spot orphaned client processes after a crashed launch. See also `list_configurations`, `launch`, `terminate_launch`.

## Result
JSON with `launches` (per-launch records), `osProcesses`, `modalWindows`, `launchCount`/`osProcessCount`, and a `diagnostics` map (`liveLaunchCount`, `orphanedOsProcesses`, `nonZeroExitProcesses`). With `includeTerminated=false` terminated launches are omitted; each launch entry carries a `consoleTail` of up to `tailLines` lines.
