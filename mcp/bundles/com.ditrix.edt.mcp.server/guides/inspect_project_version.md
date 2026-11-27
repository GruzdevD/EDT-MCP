## Read an immutable source version

For a committed tree, supply commitSha and the full authorized branch ref in place of snapshotId and side:

    {"repoId":"app","projectName":"Base","commitSha":"<final SHA>","branch":"refs/heads/main","view":"diff","baseSha":"<BASE SHA>"}

Use the same endpoints for every page. Omitting baseSha compares a single-parent commit to its parent. Rename and deletion are represented by their old and new paths. Request the base manifest to obtain file handles for removed paths. A snapshot diff compares HEAD to the selected side. Each diff item reports whether its own text patch is complete. Use content for exact bytes when a patch exceeds the limit.

Configure a project in the existing authenticated loopback snapshot policy. Add this field inside its project grant:

    "review": {"repoId":"app","workTree":"C:\\Dev\\Repository","branches":["refs/heads/main"]}

Use the alias, EDT project and capture ID to read the manifest. The server checks the captured work tree and branch against the current project policy on every page. Capture again after enabling review.

    {"repoId":"app","projectName":"Base","snapshotId":"<id>","side":"disk","view":"manifest"}

Head, index and disk have separate manifests. An unchanged file appears on each side on which it exists. Use its fileId to request exact bytes or search for a UTF-8 literal across authorized files:

    {"repoId":"app","projectName":"Base","snapshotId":"<id>","side":"disk","view":"content","fileId":"<id>"}
    {"repoId":"app","projectName":"Base","snapshotId":"<id>","side":"head","view":"literalSearch","literal":"ПроверитьИтог"}

Decode base64 pages and concatenate in byteOffset order. Search offsets are zero-based bytes. Each page carries at most 20 rows or 16 KiB of file content. Search examines at most 1 MiB of candidate byte positions per page. The source capture supports files up to 4 MiB. Pass nextCursor with identical arguments until complete is true. A reason without a cursor reports incomplete coverage. The default projectSources scope covers captured files for the allowed source extensions. Request repository scope when the check requires other files, including root CI settings. The snapshot lifetime is ten minutes.

Use baselineId for the retained first failed or broken job. To review repository-relative CI or test settings, the operator can add an exact perimeterPaths array to the project's review policy. Request scope=perimeter for a snapshot or a reachable commit. Its coverage is the configured paths only. A repository-wide query remains explicitly incomplete.

For a candidate, the project diff lists selected source changes against its captured parent, including deletions.
