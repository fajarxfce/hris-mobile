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

The catalog is an online selection capability. It does not persist file contents,
silently retry or start a background observer. The inherited generic transfer
screen is not an HRIS upload implementation.

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
published evidence. The upload core below is implemented; its confirmation screen
and authenticated downloads are the next integration work.

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
