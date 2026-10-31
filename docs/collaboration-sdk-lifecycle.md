# Product SDK session ownership

This is the implementation continuation for the pinned Android Rust SDK. Product
SDK store/session integration is still required; setup-command durability does
not establish message queue restart acceptance.

The source audit uses Rust SDK
[`8de215a0`](https://github.com/matrix-org/matrix-rust-sdk/tree/8de215a0aa0026afa5754ae5f05a6d168d1a1d60).
The existing actual HRIS fixture establishes encrypted transport and identity
mapping. Its synthetic private store key and proof session JSON are lab-only.

The product owner needs an exclusive store lock, a platform-protected passphrase
and a durable session record bound to HRIS audience/account/company/native family,
Matrix user/device and the configured gateway origin. Gateway token rotation or
app build changes must not create another crypto store. Missing/unreadable keys
must preserve the original files; no automatic reset or provider logout is valid.
Open exactly one SDK client per store lifetime and restore exactly once. A failed
open must close the client and release the owned lock without deleting data.

The pinned FFI exposes `Client.enableAllSendQueues(false)` independently of sync.
Product restoration must prove that queues can be paused before session restore
and queue-listener attachment, then explicitly resumed after current admission.
The send-queue update listener respawns tasks for stored unsent events. Current
proofs pause before enqueue in the same process; they do not establish the required
reopen ordering or durable logical-ID reconciliation. Keep one SDK retry owner.

`homeserverUrl` plus `disableWellKnownLookup(true)` prevents server discovery from
redirecting the configured gateway. Cross-signing, automatic backups and background
pagination need explicit configuration. Matrix auth restore starts background
E2EE setup tasks, including backup/recovery setup, so restoring a local store must
not be described as a proven zero-network operation. Local readiness, live request
admission and bounded request cancellation need separate verification.

The pinned `ClientSessionDelegate.retrieveSessionFromKeychain` returns a fallible
result through FFI; `saveSessionInKeychain` returns void. Rust's save callback calls
it and returns success without a persistence acknowledgement. Production handling
must therefore track a failed credential write, retain its owned pending value,
gate further delivery and reconcile persistence explicitly. Do not throw an
unhandled foreign callback exception or report an in-memory token as durably
installed. Exercise write failure, process death and recovery without discarding
the crypto store. Closing native resources inside a callback also needs explicit
ownership to avoid deadlocking its caller.

Next acceptance: protected store open/reopen and wrong-key preservation on the
dedicated emulator; one pending logical message across process death; receipt loss
and exact SDK transaction reuse; credential rotation with write failure; account
replacement during restoration; cleanup of only the owned resources. Follow with
the Apple adapter/hosted checks, app DI and chat routes. The physical iPhone waiver
does not turn missing Apple implementation into acceptance.
