# Document integration

`core/documents` owns shared HRIS document contracts and the authenticated API
adapter. Leave and expense workflows can use these contracts without depending
on each other's feature modules. This is HRIS business integration and does not
belong in the generic starter.

`LoadDocumentEvidence` coordinates current identity, workspace ownership and fresh
company policy before acquiring the account's own published revisions. It repeats
scope and availability checks after pending work, propagates cancellation and has
a finite request deadline. Ordinary policy expiry allows fresh acquisition; known
access denial or maintenance remains gated. The returned read deadline includes
company policy expiry, scheduled maintenance, workspace admission and session
lifetime.

The datasource delegates raw I/O to `DocumentsApi`. The repository maps the bounded
response inside `safeApiCall`, checking the echoed account/company/employment and
classification, accepted file declarations, unique ordered IDs and cursor progress.
An unvalidated replacement is excluded by the server's published-revision catalog.
Metadata neither grants content access nor reserves a later business attachment.

The Leave feature consumes this catalog through its own evidence picker. It owns
selection, pagination and dialog lifetime; the shared document modules contain no
Leave ViewModels or attachment policy. Only accepted personal revisions for the
form's employment are selectable. A replaced or expired dialog cannot publish
into a later opening, and pending metadata work is cancelled on dismissal.

Catalog acquisition is online. It does not persist file contents, silently retry
or schedule background work. `LoadDocumentEmployments` provides read-only employee
references without requiring self-upload or People access. `ObserveDocumentAccess`
separately reports session/company changes and read-lease expiry; the consuming
route owns that observation. The inherited generic transfer screen is not an HRIS
upload implementation.

## Local file import

`ImportDocumentFile` admits the current account/company and requires both self-read
and self-upload grants. It owns picker/import deadlines, validates provider file
declarations, rechecks admission after pending work and releases the input using
an ID allocated before selection. Local import needs no HTTP request. Known access,
maintenance and version failures remain authoritative; server upload will require
separate fresh admission.

`DeviceDocumentInputRepository` maps raw [OS picker](file-input.md) metadata inside
the file error boundary. `EncryptedDocumentFileRepository` coordinates raw input,
the [blob store](blob-storage.md), authenticated encryption and atomic key storage.
It writes 64 KiB blocks under account/company/file/offset-bound associated data.
Completion stores a SHA-256 proof only after the declared extent has been read.
Imports are serialized locally; neither repository decides admission or retries.

The file key persists separately from bearer credentials, including in protected
browser storage. A missing key never replaces retained ciphertext. Login changes
hide existing files; a subsequent admitted session for the same API/account can
recover them. Cancellation cleanup only removes an incomplete file at its observed
revision. A completed file, including a commit whose response was lost, remains
recoverable. Failed cleanup may leave encrypted staging records.

The Documents service exposes account/company-scoped local files, explicit import
and confirmed local removal. `ObserveDocumentFiles` owns session, company and read
lease checks; lease expiry cancels the storage reader even without another data
emission. `RemoveDocumentFile` checks the file revision shown in the confirmation;
a concurrently changed file cannot be deleted by an older confirmation.

The feature owns its route and MVI state. Its input operation belongs to the route,
not Activity `ON_STOP`, because an Android document picker intentionally stops the
Activity. Leaving the route or replacing its account cancels pending input; the
import use case still releases resources with bounded cleanup. State collection
remains lifecycle-aware. Known access failures hide content and require workspace
revalidation; reopening a local observer cannot bypass that gate.

Local completion is not server approval and does not make a file selectable as
published evidence. Server validation and publication remain separate from local
import and download completion.

## Resumable upload

`LoadDocumentUploadPreparation` acquires fresh company policy and the employee's
own employment references through Documents. It does not require People or Leave
access. `PrepareDocumentUpload` validates the confirmed input and freezes document,
revision and operation IDs in the encrypted file checkpoint before scheduling work.
A duplicate confirmation cannot replace the retained input.

`ReplayDocumentUpload` implements `SyncTask` for both manual and worker delivery.
Each execution has a deadline and advances at most four steps per file. Mutations
persist their attempt before HTTP, reuse the same key/body after an uncertain
response, and publish with a storage revision check. A bounded lease keeps another
process from immediately replaying pending work. Automatic attempts stop before the
manual retry limit; neither path retries indefinitely. Read-only status checks,
validation retries and cancellation belong to `ReviewDocumentUpload`, coordinated
with delivery through one application-owned mutex.

The repository decrypts at most sixteen 64 KiB blocks into one 1 MiB transport
chunk. Block authentication binds account, company, file and offset; the mutable
checkpoint is additionally bound to the storage revision. The caller releases the
transport buffer after HTTP completes or is cancelled. Repositories map API facts
and storage data; they do not decide retries, permission or work lifetime.

Only server `READY` confirms successful validation. Scanner failure, rejection,
cancellation and expiry remain distinct. A failed validation needs explicit user
confirmation before another validation command. A status check preserves a pending
cancellation even when another operation has advanced the server version. Local
deletion is blocked while remote work is active or uncertain, including after a
lost response; a known terminal server state permits removing the local copy.

## Upload presentation

An imported file opens its own route with employment, title, category and description
fields. The navigation entry owns the ViewModel, rendering state and effects. The
ViewModel coordinates document use cases; no request, file handle or SDK type enters
the page. Browsing employment references does not replace the current selection
until the employee confirms a row.

Unsent input survives Activity recreation in the navigation owner. Content remains
hidden until an admitted local read succeeds again; clearing that owner discards
the unsent form. Preparation expiry requires an explicit form refresh and retains
typed values. Confirmation freezes the upload input in storage before transmission.

The file list and detail page observe that durable record for progress. Server
inspection, rejection, technical processing failure and completion have distinct
labels. Status refresh, retry and cancellation dispatch separate typed events.
Leaving the route cancels its pending acquisition and manual action; scheduled
delivery has its own application/worker lifetime. Known access failures stop local
observation and keep data hidden through pending or failed workspace revalidation.

## Download coordination

`PrepareDocumentDownload` accepts a current catalog selection, rechecks company policy and content access,
then reserves encrypted local capacity. Existing copies can be reused only after
fresh admission. A replacement receives a new local ID so an older writer cannot
complete a removed file's replacement at a reused storage revision.

`DownloadDocumentFile` owns a foreground operation with a finite deadline. It
checks the selected revision with authenticated HEAD and requests exact ranges
with the server's revision/checksum validator. The API adapter uses `If-Range`;
an unexpected full response is rejected before reading its body. Failure codes,
including MFA and access denial, retain their domain classification.

Downloaded origins stay immutable in encrypted metadata. The repository writes
64 KiB authenticated blocks and advances progress with storage revision checks.
Interruption retains committed blocks; explicit resume starts at that offset.
No observer or worker silently retries a download. Completion reads the retained
blocks with bounded memory and verifies the entire SHA-256 before storing its
completion proof. A bad checksum cannot produce a readable completed file.
Session/company changes and cancellation stop publication, while the owner clears
each transport buffer. Imported files remain distinct from downloaded revisions;
the latter cannot enter the new-upload confirmation flow.

## Export admission

`ExportDocumentFile` copies only a completed, verified downloaded revision. It
allocates an output ID before opening the native save dialog and releases that
owner in bounded cleanup, including cancellation and late picker results. The
picker may create an empty destination; plaintext remains in encrypted storage
until fresh company policy and authenticated content HEAD both succeed afterward.
A cached completion never substitutes for current resource authorization.

The use case reads and writes at most 1 MiB at a time, clears borrowed buffers,
rechecks ownership around pending work, renews expired admission before another
chunk, and checks resource access again before completion. Selection and copying
have separate finite deadlines. Denial, I/O failure and cancellation never start
an automatic retry. A cancelled picker is a successful dismissal, not a failure.

`DeviceDocumentOutputRepository` maps the raw [file output](file-output.md) contract
through `safeFileWriteCall`. It does not choose permissions, another destination
or a retry. Native cancellation/failure may leave an empty or partial selected
file; release never deletes the user's chosen path. Browser output commits only
on completion and requires File System Access support in a secure context.

## Catalog and download presentation

Documents separates local files from the published catalog. The catalog offers
own-employment and category filters with bounded pagination. Its ViewModel owns
metadata acquisition, admission observation and expiry. An expired page requires
an explicit refresh; changing a filter cancels the previous request. Known access
failures hide metadata and remain gated through a failed retry.

Selecting a published revision prepares a local reservation and opens its download
route. That route owns one foreground action and observes committed encrypted
progress. Pause cancels pending work; Resume starts from retained bytes. Reopening
a local partial copy does not silently resume transport. Imported files retain
their upload route and downloaded revisions never expose the upload form.

Save copy dispatches the export use case. Dismissing the native dialog leaves the
completed download available. A successful save is durable screen state, not only
a transient effect. Pending export belongs to the navigation route and survives
the Activity stop caused by Android's picker; leaving the route cancels it. A
content denial hides the file until explicit resource revalidation succeeds,
including when a workspace refresh succeeds but the resource remains denied.
