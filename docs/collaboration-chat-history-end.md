# Restoring the end of older messages

Closing and reopening a room must not advertise older messages after its retained
provider traversal is exhausted. Matrix room-state references remain durable even
though they do not produce canonical message originals. Previously the in-memory
scan hid the control, but restoration required *every* reference to be resolved;
retained membership/creation events made the control reappear.

`ReadCollaborationHistoryEnd` owns this decision in domain. It coordinates the
traversal, local admission, session and workspace repositories directly, reading
bounded local pages until it finds a possible message or reaches the end of the
original finite snapshot. The ViewModel consumes the result and owns cancellation. Encrypted messages,
ordinary messages and stickers keep older history available. Other references
remain retained and unresolved; no fabricated original, acknowledgement, migration,
provider read or decryption attempt is needed to restore the end. Existing installs
benefit from their already-stored traversal metadata.

The use case captures one local owner for the entire scan and rechecks that owner
and admission around every page. It cannot combine snapshots from replacement
sessions. `CollaborationHistoryCompletionPolicy` owns the supported-message and
stable-traversal decisions, shared with explicit page loading. The walk requires increasing
reference sequence, unchanged generation/reference count/provider position, and a
committed provider end. Concurrent replacement/growth leaves history available;
resolution-only revisions can proceed. Cancellation propagates and the owning
ViewModel releases its timeline. Canonical local-history pagination still controls
whether older cached messages are available independently of this provider end.

Common ViewModel regression covers empty state-only rooms, more than two metadata
pages across repeated room recreation, a real pending encrypted message behind
metadata, concurrent growth/replacement and cancellation. The actual release34
receiver also reproduces the disappearing/reappearing control with its retained
room. [Measurement](measurements/chat-history-end-2026-11-05.json).

This is HRIS communications policy; no Starter foundation change is required.
The explicit page loader still coordinates acquisition use cases under its screen
lifetime; that larger orchestration remains a separate boundary review. This
increment does not move SDK or network operations into local end restoration.

Domain ownership validation, 6 November 2026: 15 new common cases passed on both
desktop and browser. Full root/Android/Web checks passed 7,056 freshly executed
cases across 911 suites in 1,089.263s, with zero failures/errors/skips or Android
lint findings and 4,215 unchanged source inputs.
[Boundary evidence](measurements/collaboration-history-end-domain-2026-11-06.json).
This does not resolve or accept the separately reported slow pending sends.
