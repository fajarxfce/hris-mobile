# Atomic incoming-demand storage

The account/space queue and its independent incoming recovery wake commit in one existing
ReplicaStore replacement. The queue is one encrypted document with a small validated checkpoint.
A failed capacity, ownership or exact-snapshot comparison changes neither queue nor wake.
Cancellation may lose the response after commit; it cannot retain half the transaction.
An owning request must confirm the exact retained state before scheduling or advancing a scan.

The queue survives another database connection and same-account login replacement. Access
requires the current session's account handle; rotating credentials does not relabel ownership.
Cipher authentication binds audience, account, space, generation, document identity and version.
Checkpoint/document reads share one transaction and reject missing, inconsistent or unsupported
metadata. Schema, canonical positions, phases and the 64-room limit are validated after decrypting.
Plaintext is bounded to 65,536 bytes and encoded document content to 131,072 characters, including
64 maximum-length UTF8 event IDs. Generic checkpoint/document limits and existing formats remain
unchanged. Temporary plaintext/key buffers are cleared through the owning cipher/key boundary.

Phase and deferral updates preserve the existing wake. Empty demand does not acknowledge it:
clean worker completion has a separate admission owner. Incoming, Company delivery and Space
delivery keys remain independent even when their generation strings happen to match. A stale
acknowledgement cannot discharge newer incoming work. Repeated current/absent reads allocate no
content key, and standalone wake operations store no message or account payload.

Sixteen real-store scenarios cover maximum size, reopen/reauthentication, independent wakes,
capacity rollback, concurrent producers, cancellation after commit, stale CAS and acknowledgement,
phase preservation, cross-space/account ownership, cipher/metadata substitution, malformed payloads
and mutation of caller-owned lists. SQLite and actual Chromium pass 118 focused cases including
existing recovery controls in 290.724 seconds; iOS data compilation and quality checks
pass. The first focused run exposed the checkpoint size mismatch and a stale-session test caller;
both were corrected before the accepted run, with failed evidence retained privately.

Full validation passes 10,511 cases in 644.393 seconds
(8,492 fresh and 2,019 exact accepted controls), with
4,810 fixed inputs and stable Inspector sources. Root, Android host/release/lint, Wasm and iOS
application source, Chromium, proof build/unit tests, formatting, architecture and detekt pass.
[Focused measurement](measurements/collaboration-space-incoming-demand-storage-focused-2026-11-10.json)
and [full measurement](measurements/collaboration-space-incoming-demand-storage-full-2026-11-10.json).

This storage capability enables no new product consumer, SDK callback, worker or UI behavior.
Next, the owning request must distinguish exact acquired source IDs from provider pagination
tokens. The admitted worker must verify provider EOF and unresolved sources before completion,
retain bounded rescan/scan claims, and recover process loss without recreating native clients.
No reusable Starter foundation change was needed for this HRIS storage implementation.
