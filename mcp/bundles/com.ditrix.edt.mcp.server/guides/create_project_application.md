Creates a NEW application for an EDT configuration project, bound to a git BRANCH context and pointing at an EXISTING infobase — WITHOUT creating or registering a new database or a new infobase reference.

`create_infobase` always registers/creates a base, and `set_branch_infobase` re-binds an EXISTING application (so a new branch SHARES it). This tool fills the gap between them: it creates a NORMAL application over a base that already exists in EDT's registry. EDT has no "create application" operation of its own — an application surfaces by associating an infobase reference with a project — so this tool associates the EXISTING base (named via `list_infobases`) with the project's branch context as NOT-synchronized (the same setting `create_infobase` uses — this makes EDT BIND the reference without trying to reconnect/lock the base, deferring the config load to a later `update_database`/`launch`). The surfaced application inherits the base's name. Nothing on disk or in the database is touched, and — critically, matching EDT's "existing base" dialog — NO new infobase is added to EDT's registry, so `list_infobases` is unchanged.

## Parameter details

- **projectName** (required): the EDT configuration project to create the application for.
- **branch** (required): the short branch name (e.g. `feature/x`) whose context to bind the new application to.
- **infobaseName** (required): the name of the EXISTING infobase to point the new application at, exactly as `list_infobases` reports it.
- **applicationName** (accepted for compatibility, IGNORED): an application over an existing base inherits the base's name — there is no separate application name to set.
- **setDefault** (boolean, default false): also make the new application the branch context's DEFAULT infobase.
- **access** (optional, `INFOBASE` | `OS`): authentication kind to connect to the base this new application points at. `INFOBASE` (default) uses 1C user auth; `OS` uses OS authentication. See **Silent authentication** below.
- **user** (optional): infobase user to authenticate as for the new application's connect (an EXISTING user). Omit/empty for OS auth or a userless base.
- **password** (optional): the user's password. Never echoed back in the result — only `passwordSet` tells whether one was stored.

## Silent authentication (no access-settings dialog)

Creating an application associated with a base makes the read-back connect to that base, and a base that needs credentials would otherwise fail the connect and pop EDT's "Configure Infobase access Settings" dialog (which hangs or flashes in an unattended run). If the base needs auth, pass the credentials HERE so they are stored on the new application's infobase reference BEFORE the association — the connect then authenticates silently:

- OS authentication: `access="OS"` (`the connect uses the current OS user; no password needed`).
- Login/password: `access="INFOBASE" user="..." password="..."` (or just `user`/`password` — `INFOBASE` is the default).

The stored credentials PERSIST in EDT settings, so the same base/application connects silently on later `update_database` / `launch` / read-backs too. They are the same store `set_infobase_credentials` writes, keyed by the new reference. The password is never returned or logged — the result reports only `user`, `access` and `passwordSet`.

## Result

JSON with `success`, `project`, `branch`, `infobaseName`, `applicationName` (the APPLICATION's name — the existing base's name), `binding` (whether it surfaced: `BOUND` / `NOT_BOUND` / `UNVERIFIED`), `applicationId` (present when the read-back found it), `applications` (evidence of what the read-back observed), `bound` (`{infobases: [...], defaultInfobase}` — the branch context after the change) and, when auth params were passed, `credentialsStored`, `user`, `access`, `passwordSet`.

`NOT_BOUND` is NOT a silent failure: the association was requested and raised no error, but no matching application appeared within the read-back budget — the honest verdict because an application surfacing into a project-level application is asynchronous. Check `get_applications` for the actual state.

## Typical usage

```
# 1. List the existing bases to name one.
list_infobases  projectName="mom"

# 2. Create a new application for a branch over the base "Инвест", OS authentication (no dialog).
create_project_application  projectName="mom"  branch="feature/x"  infobaseName="Инвест"  access="OS"

# 3. Create with explicit login/password, and make it the branch default.
create_project_application  projectName="mom"  branch="feature/x"  infobaseName="Инвест" \
    setDefault=true  user="Администратор"  password=""
```

## Relationship to the other application/base tools

| Goal | Tool |
| --- | --- |
| List the project's applications | `get_applications` |
| List the registered infobases (to name one) | `list_infobases` |
| Register/create a NEW infobase | `create_infobase` |
| Re-bind an existing application to a branch context (share it) | `set_branch_infobase` |
| Create a DISTINCT application for a branch over an existing base | `create_project_application` (this tool) |
| Delete an application WITHOUT touching the base | `delete_project_application` |
| Store connection credentials for an existing application/launch | `set_infobase_credentials` |

## Gotchas

- **No new database and no new infobase reference**: this tool binds the EXISTING base — the new application points at the SAME physical base and `list_infobases` is unchanged (no "new base in the list", matching EDT's "existing base" dialog). To actually register/create a base, use `create_infobase`.
- **The application inherits the base's name** — `applicationName` is accepted for compatibility but ignored, because there is no separately-named reference to give it.
- **`NOT_BOUND`/`UNVERIFIED` means check `get_applications`**: the association is asynchronous; if it did not materialize in the read-back budget, look at the EDT log and `get_applications`. A base whose connect cannot complete (missing credentials) is the most common cause — pass `access`/`user`/`password`, or store them with `set_infobase_credentials`, then retry.
- **Credentials store against the existing base's reference** and select existing users; they do not create them. A demo/userless base may use an empty password or OS auth.
- **Cyrillic names are fine**: pass the base and application names exactly as `list_infobases`/`get_applications` report them.
