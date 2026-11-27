## Read change documents

Grant read for this change in the project's existing review policy:

    "changes":{"fix-rate":["read"]}

List the permitted documents in an existing change. Then use kind and itemId from the list. The optional revision binds the request to that exact repository, project, branch, change, document, source and content.

    {"repoId":"app","projectName":"Base","branch":"refs/heads/main","changeId":"fix-rate","view":"list"}
    {"repoId":"app","projectName":"Base","branch":"refs/heads/main","changeId":"fix-rate","view":"read","kind":"delta","itemId":"currency-rates","commitSha":"<reachable SHA>"}

Decode base64 before checking the SHA-256 digest. gitBlobId uses the Git blob header and exact bytes, including BOM and line endings, as in git hash-object. Use it for designHash. A list covers up to 128 documents and 4 MiB combined. Individual documents support 256 KiB. Read raw, boundary and manifest by evidence ID. Hash the decoded raw reviewer body before adding the report header, then verify the hash of the complete published report separately.

Document paths must stay within the granted repository. Nested checkouts, linked worktrees and submodules are rejected.
