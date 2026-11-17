# import_project

Import an existing project directory into the EDT workspace (linked, not copied) and wait for it to become ready. Parameters and examples: get_tool_guide('import_project').

## Parameters
| Parameter | Required | Type | Description |
| --- | --- | --- | --- |
| path | yes | string | ABSOLUTE path to the directory that contains `.project` (used in place, linked — not copied). |
| projectName | — | string | Optional new name for the project in the workspace. Needed when the `.project` name already exists in the workspace (e.g. the main checkout is imported). The rename is workspace-level; the on-disk `.project` file is NOT rewritten. |

## Guide
Imports a project from disk into the current EDT workspace by linking its location (the project files stay where they are). After the import the tool waits for the project lifecycle to reach STARTED and, when the directory is a git repository, connects the project to the shared repository (EGit `ConnectProviderOperation`) so team operations (branch list, commit, push) work immediately.

Typical loop for a fresh slot from git: `git clone`/checkout the branch to a directory → `import_project({path: "<dir>", projectName: "afm.UMFO-<NNNNN>"})` → create the application/infobase. If the `.project` name collides with an already imported project, pass `projectName` — the workspace name changes, the disk copy is untouched.

## Result
JSON with `success`, `action`, `project` (workspace name), `location` (absolute linked directory), `state` (`ready` when lifecycle STARTED was reached, `created` otherwise), `renamed`, and git-connection fields (`gitConnected`, `gitRepository`, `gitBranch`, `gitWarning` when the connect failed but the import succeeded).
