## Write an exact working-tree revision

Configure grants for the named change. write permits normative edits, report permits buildReview, create-only evidence (raw, boundary, manifest) and task completion checkboxes. append permits typed build events in iterationLog. Build events carry an operation UUID and a base64 payload. Human approval is obtained separately by the confirm workflow.

    {"repoId":"app","projectName":"Base","branch":"refs/heads/main","changeId":"fix-rate","kind":"boundary","itemId":"review-1","operation":"create","expectedRevision":"absent","base64":"<bytes>"}

Use read_change_artifact to obtain the current revision for put. Concurrent changes cause a conflict. checkbox accepts a complete tasks document in which only unchecked task boxes become checked. For append, send the same operationId, original expectedRevision and identical bytes on retry. The log retains the operation marker and the server reconstructs the original receipt even after later appends. Use a new UUID for a distinct event.

Writes require local transactional NTFS support and exclusive access to the target. On conflict, read the file before deciding whether to retry. The operation updates the working tree independently of Git staging.
