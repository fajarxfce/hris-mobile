# File transfers

`core/transfers/domain` owns admission, session policy, pause/resume and worker batches.
`core/transfers/data` implements the input, durable queue and HTTP repositories.
`features/files/presentation` owns the MVI queue screen. Open **Account → Files** to
upload or download the sample report. Editors can upload; viewers can download.

## Ownership and storage

- `TransferInputSource` supplies raw metadata and a cold `Flow<ByteArray>`. The sample
  generates blocks. An OS file adapter can implement the same port and must close
  descriptors, security-scoped access and callbacks on cancellation. Pass it as
  `transferInputs` to `createAppContainer`. No file picker or public-folder export is bundled.
- `EnqueueUpload` checks the live session/grant and file limits before staging input.
  Import has rendezvous backpressure, a two-minute deadline and bounded cleanup.
  Acquisition and checkpoint cancellation both remove a partially created draft.
- SQLite/IndexedDB stores metadata and blocks up to 256 KiB. A checkpoint and its
  optional block commit in one transaction with session ownership and version checks.
  Old account results and paused/removed job results cannot replace a newer checkpoint.
- Limits are 50 MiB per file, 200 MiB reserved per queue and 20 entries. Completed
  entries count toward admission until removed. Upload content is removed on completion;
  downloaded content stays private to the active session. Account transitions clear both.
- `ReadDownloadedFile` exposes completed downloads as bounded blocks. An export feature
  should coordinate the queue and an output repository in its own use case, with its
  destination and access policy there. Never collect a whole large file into screen state.

The app database uses platform sandbox storage, without additional content encryption.
Browser storage remains subject to browser quota and eviction. Protected document products
can replace the queue storage adapter with their encryption/retention implementation.

## Resume and execution

`SyncTransfers` is one singleton `SyncTask` per container. It processes at most 32 chunks
per invocation. Android uses WorkManager; iOS, desktop and Web use the existing foreground
runner. iOS can also process a batch during its existing BGAppRefreshTask opportunity,
with cancellation when the OS execution window expires. This does not add iOS background
URLSession or browser service-worker transfers.
A stopped/cancelled worker leaves a durable checkpoint for the next scheduled run.
Restart recovery requires restoration of the same session. Web sessions are in memory;
a reload ends that session and account activation clears its old queue.

Pause/account-change observers are children of the active batch and cancel pending I/O;
all observers are cancelled when that batch finishes. Optimistic writes reject late results.
Only use cases decide retry/status policy. The scheduler owns backoff. Successful foreground batches continue after a short yield;
Android continuation uses WorkManager retry timing. Network failures stay
queued; permanent failures require explicit resume. Remove deletes the local job/content.
The backend must expire abandoned upload sessions independently.

Uploads use an idempotent creation key, then read the authoritative offset with HEAD before
sending more bytes. A partially accepted block resumes from its remaining bytes. If a PATCH
response is lost, HEAD reconciles it on the next run. PATCH is not automatically replayed
by the authentication plugin. Downloads require a matching strong ETag and exact
Content-Range, and reject short/oversized blocks. A changed remote version requires a new
download, preventing mixed content.

The queue screen publishes integer percentage changes at most every 200 ms. Stable row IDs
and immutable row models isolate item updates. Its observers stop with the route lifecycle;
worker execution is independent. There is no application-wide progress timer.

## Backend contract

All endpoints use the configured first-party HTTPS API origin and originating session.
The HTTP client rejects cross-origin authentication and redirects. This adapter does not
accept arbitrary remote URLs or persist signed URLs. Object storage can sit behind these
endpoints; a direct-storage adapter needs its own explicit origin and credential boundary.

| Request | Response |
| --- | --- |
| `GET files/{id}` | `{"id":"report","file":{"name":"report.txt","mediaType":"text/plain","size":1024},"version":"\"v1\""}` |
| `POST files/uploads`, JSON file metadata, `Idempotency-Key: <transfer-id>` | `{"id":"upload-id"}`; repeat keys return the same resource |
| `HEAD files/uploads/{id}` | 200/204, `Upload-Offset`, `Upload-Length` |
| `PATCH files/uploads/{id}`, `application/offset+octet-stream`, `Upload-Offset`, `Tus-Resumable: 1.0.0` | 204, authoritative `Upload-Offset` |
| `GET files/{id}/content`, `Range: bytes=start-end`, `If-Match: "v1"`, `Accept-Encoding: identity` | 206, exact `Content-Range`, matching `ETag`, requested bytes |

Resource IDs are 1–128 ASCII letters/digits, `_` or `-`. This is a small offset protocol
inspired by tus; the creation/metadata API is application-specific, not a full tus client.
The server must authenticate and authorize every operation, enforce size/type/storage
limits, commit bytes before acknowledging offsets, and scope idempotency keys by owner.
Configure Web CORS for these methods/headers and expose ETag, Content-Range, Content-Length,
Upload-Offset and Upload-Length. Range responses must remain uncompressed.

The demo server recognizes only its generated report. It retains bounded metadata/offsets
in process and generates response blocks without retaining a file buffer. Restarting the
app also restarts that fixture backend; real server-side offsets require the real backend.

## Validation

Shared transaction tests run against SQLite and real IndexedDB, including reopening and
account isolation. Domain tests cover denial, failed/cancelled import, pause, account change,
late completion, worker cancellation and offset reconciliation. HTTP tests cover malformed
ranges, version changes, truncation, oversize, redirects and cancellation. MVI tests verify
progress conflation and observer disposal; the composed app test performs both sample flows.
These are behavioral checks, not a device heap or frame-time benchmark.
