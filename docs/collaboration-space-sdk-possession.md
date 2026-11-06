# Original private-account identity

`ReadCollaborationSpaceSdkPossession` reads the existing private Matrix account and
compares its identity with the immutable legacy-device registration. A same-account
login can inspect the original physical namespace even when its native parent or
company access has expired. This local observation grants no live access, server
receipt, enrollment permission or completed ownership transfer.

The use case checks the current local account before and after both repository reads.
It requires the exact retained registration operation and complete original provenance.
Pending and unconfirmed registration outcomes remain uncertain. Missing private
material is a conflict; a different private identity is rejected. Neither result
creates a replacement device or replays a command. Reading is bounded by a five-second
coroutine timeout; synchronous native work finishes before cleanup, so this is not a
promise that the underlying native operation is interruptible at that deadline.

`StoredCollaborationSdkPossessionRepository` acquires the existing-only directory
lease, reads its protected credential record, invokes the raw private-account reader,
and maps the derived public keys to the established HRIS fingerprint. The lease covers
all those reads and mapping. Credential and directory resources close on success,
failure and cancellation, preserving the original failure if closing also fails.
Directory/crypto/credential absence returns a distinct missing result without SDK
initialization. Corrupt data remains a storage failure. A missing state database or
credential rotation's `initialized=false` does not erase an extant private identity;
those facts remain in the returned inventory for the eventual adoption workflow.

The Android datasource invokes the paired SDK's synchronous
`readExistingCryptoIdentity` on an I/O dispatcher. It returns only raw public identity
fields and preserves the SDK's closed technical exceptions. The native operation must
finish before cancellation releases its caller's lease. No client is constructed,
no session restored and no request made. Raw filesystem paths and passphrases never
enter domain entities. The shared record mapper is also used by metadata inventory;
metadata alone still cannot establish possession.

Android application composition supplies the private reader and original protected
credential factory. Unsupported hosts explicitly return unavailable. The data module
owns constructor bindings; no ViewModel or product navigation workflow changes.

The isolated implementation and actual application DI now compile. Focused acceptance
covers 61 distinct cases, including 16 new cases for account replacement, original keys
and namespace, absent/corrupt storage, retained uncertainty, resource failures, the
canonical root alias and cancellation while synchronous native work holds the file lock.
The first gate ran 61 cases in 623.964s with one failed test assertion: coroutine stack-
trace recovery copied an exception with the original as its cause. Only that assertion
changed; the targeted Android-host retry verifies the technical type and original cause.
No production source changed between those gates.

The paired SDK4 Android proof separately preserves original private keys across an
SDK3-to-SDK4 ARM64 upgrade and an x86_64 process restart. Both use the canonical database
path supplied by the same inventory lease; raw Android aliases remain rejected.
The normal published-SDK4 root/Android/browser gate passed 7,388 fresh cases in 963
suites, with zero failures/errors/skips, unchanged inputs and zero Android lint issues.
The first full attempt had passing tests/build/lint but quality tasks incorrectly
included private `.work` generated bindings. Excluding that scratch tree in both
quality source selection and Detekt was the only source change before the full retry.
All 37 original scratch files remain present; Detekt and formatting now pass.
[Measurements](measurements/collaboration-space-sdk-possession-2026-11-06.json).
Integration with original-parent recovery, provider publication, stop/join, freeze/drain
and adoption remains required. Apple native implementation is still separate work;
its physical-test waiver does not imply runtime support.
