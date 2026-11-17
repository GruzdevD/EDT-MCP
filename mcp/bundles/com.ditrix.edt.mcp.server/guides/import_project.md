Registers an EXISTING EDT project directory - one that already contains a `.project` file (a git worktree, a second checkout, a copy) - into the workspace as a LINKED project: the files stay where they are, nothing is copied or moved, and the project opens and builds like any workspace project.

## When to use it (parallel task slots)

To run 2-3+ tasks in parallel inside one configuration project, give each task its own git worktree and register it:

1. `git worktree add ../afm-feature-branch <branch>` from the main checkout.
2. Call `import_project` with `path` pointing at that worktree directory. If its `.project` name collides with the imported main checkout, pass a distinct `projectName` (e.g. `afm-slot2`). The tool also EGit-shares the project (the headless equivalent of Team > Share Project) when a `.git` is found at or above the directory - this makes the slot resolve to ITS OWN repository/branch instead of falling back to the main checkout's mapping. Without it, branch-infobase association machinery reads the main checkout's CURRENT branch and attaches the wrong infobase context.
3. Work against the slot by its project name in the EDT tools. The slot needs its own infobase binding: `create_infobase` (register mode) + `set_branch_infobase` (attach), then `update_database` against the slot.
4. When the task is done: `delete_project` (default `deleteContent=false` keeps the worktree on disk) and remove the worktree with git.

## Parameter details

- **path** (required): ABSOLUTE path to the directory that contains `.project`. The directory is used in place (linked, NOT copied). To start an EMPTY new project use `create_project`; to build a project from a configuration XML dump use `import_configuration_from_xml`.
- **projectName** (optional): register the project under this workspace name instead of the name in the on-disk `.project`. Needed when that name already exists in the workspace (e.g. the main checkout is imported). The rename is workspace-level: the on-disk `.project` file is NOT rewritten.

## Result

JSON with `action` (`imported` = registered and opened, `opened` = existed closed and was opened, `alreadyOpen` = nothing to do, `importing` = still running past the wait window), `project`, `location`, `state` (`ready` when the DT lifecycle STARTED was reached within the wait window, `created` if still indexing), `renamed`, and a `message`.

Git-sharing fields (fail-open: a sharing problem never fails the import):

- `gitConnected` - whether the project is EGit-shared after this call.
- `gitRepository` - absolute path of the repository the project is shared with, when known.
- `gitBranch` - current branch short name of the shared repository, when known.
- `gitWarning` - present when EGit sharing was skipped or failed. The common case: no `.git` anywhere above the project (a plain copy of the project subdirectory without the repository) - the slot will fall back to the main checkout's git context for infobase associations. Copy the whole clone (including `.git`) or use a git worktree as the slot source.

## Gotchas

- Name conflict: if a project with the effective name already exists at a DIFFERENT location, the tool refuses; pass a different `projectName` or remove the old project first (`delete_project`).
- If the same project already exists at the SAME location, the call is idempotent (it just opens/reports it).
- A directory without `.project` at its root is rejected with a hint to `create_project` / `import_configuration_from_xml`.
- First build of a freshly imported configuration can take a while: `state='created'` means the project is registered but still indexing; re-check with `list_projects`.
- The import runs as a workspace Job; a call that exceeds the 120-second wait window returns `action='importing'` rather than an error - do not re-issue the call, check `list_projects` instead.
- Terminate any running launch against the main project before switching work onto a slot, so the two do not hold the same resources.
