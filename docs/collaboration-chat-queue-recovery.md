# Original chat queue recovery

Status: automated and actual SDK acceptance passed on 5 November 2026. Release33 remains on
the phone; OLD AND NEW Bima sends are not yet accepted as fixed.

The release33 closed diagnostics at10:31:04 UTC reported four retained Rejected
markers, two delivery QueueUnresolved markers and93 HTTP200 observations, with no
process-crash marker in that captured buffer. No message content, tokens, room IDs
or account IDs were collected.

Credential replacement previously preserved the actual encrypted Matrix store and
its keys but replaced the bootstrap operation used by the app's message binding.
Earlier Staged records no longer matched the queue. Staging a new message then saw
unmapped native transactions, while delivery rejected the older mapping. Separately,
enabling a Matrix room queue does not release a request parked after an
unrecoverable API error.

## Store continuity and legacy records

The protected SDK credential record now retains an optional queueOperationId. New
stores use their creation operation; confirmed credential replacement preserves
that operation while advancing bootstrapOperationId and the gateway version.
Admission still uses the current bootstrap. Token refresh, same-store replacement,
and identity handover keep the original queue namespace.

Previously written records without the new field remain readable. For affected
legacy Staged/Sent records, a different bootstrap operation is accepted only when
the exact transaction exists in a complete paused native queue snapshot and the
native family, device and room match. The application record is authenticated under
its account/company partition. Every native pending ID still needs an unambiguous
application mapping. Receipts keep the original record's device binding and
transaction. An absent transaction, different native family/device/room, an unknown
queue entry or uncertain Staging work does not authorize a replacement send. No
message is matched by body or recipient name.

## Retained API failure retry

After fresh room admission, the exclusive paused writer copies a bounded native
timeline snapshot. Only owned local transactions present in the captured mapped
queue and parked with GenericApiError or ExpiredAccessToken are candidates. A
window retries at most eight original event transactions through SDK retrySend;
it never creates a second transaction or changes the original content. SDK trust
and encryption checks run normally. Identity, verification and insecure-device
errors are excluded. Recoverable failures stay with the SDK's existing retry.
Unknown queue entries prevent both retry and enablement.

The runtime owns the snapshot listener, retry operation and queue listener.
Stopping during acquisition prevents late retry/enablement. Listener disposal is
joined before retry; incomplete cleanup retains the handle for parent cleanup.
Interrupted waiters cannot abandon native store work.

The pinned SDK26.10.23 source is
[8de215a0](https://github.com/matrix-org/matrix-rust-sdk/blob/8de215a0aa0026afa5754ae5f05a6d168d1a1d60/crates/matrix-sdk/src/send_queue/mod.rs).
Its permanent queue failure needs unwedge/retry; room enablement alone is insufficient.

## User interface

An empty conversation keeps its composer available and uses “Messages will appear
here.” No manual Load messages button or older-history spinner appears in an empty
room. Existing automatic synchronization continues. Older-history controls remain
available for a conversation that already has rows.

## Validation and continuation

[Source-bound evidence](measurements/chat-queue-recovery-2026-11-05.json).
The focused pass completed380.779s/54 fresh/zero failures/errors/skips; the43-case
domain subset passed earlier. Root :check, Android host/app compilation and both
browser suites passed with1,241 fresh cases and no test failures. That invocation
failed only at APK packaging. A fresh unchanged-source package/proof-test invocation
passed55.536s/18 fresh cases. All4,070 input hashes match between these runs.
The initial package error did not include a cause; do not attribute it to memory or
storage. The successful retry required no source change. Earlier failed fixture
version/token shapes and the obsolete Indonesian UI assertion remain recorded.
Three fresh normal/large-text renders are reviewed and hash-bound.

Both actual native suites passed165.743s/two cases/zero skips, with12,281 unchanged
combined source inputs and exact APK02269c9531c519579d334e25af2ea1caa58fb2cfa81f3e6a1ac284fb3961bb6e.
The auth scenario parks one real API-rejected send, replaces rejected credentials
on the original store, verifies the stable queue binding, then reproduces a legacy
protected record lacking queueOperationId. It stages a new message while the old
one remains pending and delivers both, preserving each original transaction and
receipt binding. The peer actually decrypts both. Server assertions require exactly
two accepted encrypted sends and one injected rejection; original keys, device and
HRIS parent remain intact. The other suite retains quoted reply, process death,
receipt recovery and cold history with an unreachable gateway. Driver31 pass0.130s.
Owned emulator stopped1.604s with transport absence verified.

The fixture-only legacy record mutation never runs in the employee app. Old server
fixtures remain compatible with the new probe, but the new driver requires all
queue acceptance flags. No broader protocol acceptance follows from an old fixture.

NEXT: commit/push, build/lint clean signed release34, verify the exact APK on the
owned emulator, announce EACH installation and use install-only on10.77.77.3:5555.
Read ordinary phone-use diagnostics before accepting the physical Bima issue as
fixed. Live API/worker11a98115/schema116 remain unchanged; Main changes only test
fixtures. All54 PRD groups remain open; continue the communications long run.
