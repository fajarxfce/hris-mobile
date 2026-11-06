# Original SDK store inventory

The local inventory reads the physical namespace retained by an organization device
registration. It reports directory presence, the two original SQLite files and
non-secret protected-record provenance. These facts do not prove private-key
possession, current native authority, successful delivery or completed migration.

`ReadCollaborationSpaceSdkInventory` owns a finite five-second local read. It checks
the current local account before and after each repository read and validates the
complete retained registration. Same-account login replacement can inspect an old
native parent; ordinary age, current HR company and expired remote credentials do
not hide that owned record. A foreign account or malformed registration cannot
reach private storage. Pending and uncertain registration intent remains readable
without becoming a registration receipt.

The use case constructs physical ownership from the registration's immutable legacy
company, native parent and device. It never substitutes the currently logged-in
native parent or organization ID into that namespace. Its pure policy checks exact
Matrix user/device provenance and any retained handover fingerprint. Missing files
or a missing protected record remain incomplete local facts, never permission to
enroll a replacement device or reset keys.

`StoredCollaborationSdkInventoryRepository` maps two datasource contracts under
`safeStorageCall`: an existing-directory lease and the named protected credential
record. Scope acquisition, record decoding and resource cleanup share that boundary.
The protected codec validates the full store identity, account/company/native parent,
configured endpoint and record format. The result contains no credentials, passphrase,
private keys or paths. Credential rotation may reset `initialized`; that flag alone
cannot distinguish first initialization from loss of an existing store. Original
message queue IDs and staged handover remain unchanged.

`FileCollaborationSdkInventorySource` only inspects an existing namespace. It never
creates the SDK root, device directory, lock, cache or database. Existing namespaces
require the original regular `owner.lock` and share its exclusive lock with runtime
acquisition. Busy stores fail; inspection does not open a second SDK. While that
lease is held, only the exact `matrix-sdk-state.sqlite3` and
`matrix-sdk-crypto.sqlite3` files are checked for regular-file/nonempty metadata.
Cache files and arbitrary occupancy are not substitutes. Redirected child paths,
invalid entries and I/O failures are not converted into absence. It opens no database
and reads no message or key content. The Android protected-record implementation may
use its own record lock/recovery; this is not a claim of zero filesystem activity.

Both resources close on success, failure and cancellation, including cancellation
at the dispatcher return boundary. An original exception survives close failures.
Android application composition supplies the no-backup root and configured gateway
endpoint through an application-context factory. Unsupported runtimes return their
existing unavailable result. No ViewModel, UI, runtime restoration, live gateway,
SDK distribution, migration schema or installed phone build changes in this step.

## Validation and next work

[Acceptance evidence](measurements/collaboration-space-sdk-inventory-2026-11-06.json)
records 43 focused cases and a complete 5,405-case gate, both with unchanged inputs
and no failures/errors/skips. Root, Android host/proof compilation, browser,
Android assembly/lint and architecture/format pass. Android lint has zero issues. Real-file tests cover unchanged
paths/bytes, mutual exclusion with runtime acquisition, missing/obstructed/symlinked
files and cancellation. Repository tests exercise original, rotated and frozen
protected-record formats without writes; use-case tests cover account replacement,
old-parent lookup, local expiry, malformed provenance, timeout and cancellation.
Actual app DI is checked. Synthetic database contents are file-metadata fixtures;
they do not establish that a crypto account can be decrypted.

Next: prove possession of the original private store, handle native hard-parent
recovery, stop/join the old SDK and coordinate freeze/drain/adoption before routing
organization foreground/background work. The current company runtime stays intact.
Phone50's long-pending report remains open until there is a physical send result.
All remaining chat, voice/video and meeting requirements continue independently.

The first native CI run at35dfec82 also passed on ARM64 and x86_64:403 cases in68
suites per ABI, zero failures/errors/skips. Exact REST artifact digests, original
queue recovery, cold history, quoted replies, worker and notification cleanup were
audited without redispatch. [CI evidence](measurements/collaboration-space-sdk-inventory-ci-2026-11-06.json).
The first control run's portable job passed; Apple host compilation remains running.
