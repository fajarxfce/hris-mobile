# Room recovery progress and captured history references

Room recovery retains a bounded encrypted position for each owned account/company.
It is independent of selected workspace, transport expiry, provider history positions
and platform job input. No native device or room permission is inferred from a saved
position. The raw account check still fences every read/write by the current local
session; same-account reauthentication can reopen retained progress.

`ReadCollaborationRoomRecovery` reads a coherent local snapshot. The single app
processing owner uses `UpdateCollaborationRoomRecovery` to persist Attempt before
external work, then Complete for that exact in-flight room. Attempts advance strictly
by canonical conversation UUID. Advancing past an interrupted attempt preserves an
uncertain retry; subsequent successful rooms cannot clear it. Healthy remaining work
sets a separate continuation flag. Neither failed work nor a long first-room backlog
should reset the seek and indefinitely exclude later rooms.

Only that processing owner may Finish after validating the live directory end.
Finish commits a fresh empty position and then returns Covered, Continue or Retry
from the previous sweep. An unfinished final attempt requires Retry; failures win
over healthy continuation. Covered describes attempted coverage, not message delivery
or notification acknowledgment. Even an empty directory Finish uses compare-and-set,
so an older snapshot cannot erase a competing attempt. Every commit uses a new
generation to prevent an A-to-B-to-A position being mistaken for the original row.

The repository uses the existing protected history key and raw ReplicaStore under
`collaboration-history/room-recovery/<company>`. Room IDs and outcome flags are inside
bounded authenticated ciphertext. AAD binds format, audience, account, company and
generation; replaceable local/native session IDs are not ciphertext ownership.
Malformed, transplanted or oversized envelopes cannot clear progress. Retained room
recovery rows also prevent silent replacement of a lost history key.

Both domain operations preserve known list/company denial before and after pending
I/O. Cancellation and late account replacement prevent publication; a committed
position remains available for later recovery. The repository performs no scheduling,
permission renewal, native acquisition or automatic retry. Its singleton and both
use cases are registered through the owning data module.

`ReadCollaborationHistoryReferences` and `ReconcileCollaborationHistoryReferences`
now accept `CollaborationLocalScope`; their String entry points still delegate to
Selected. Captured uses the supplied account/company and never consults or changes
the selected workspace. The original bounded metadata validation, detached snapshot,
exact original/tombstone matching, retained denial and cancellation rules remain.
Resolution changes local reference facts, while the provider's saved position remains
unchanged. Neither operation acquires SDK/network content or resets foreground Older
history traversal. Already-cancelled calls stop before admission/reference storage.

The [validation record](measurements/chat-room-recovery-2026-11-02.json) distinguishes
the initial room-only focused run from the final scope-inclusive full checks. Twelve
room-domain and nine shared SQLite/IndexedDB cases cover local ownership, fairness,
independent flags, restart/reauthentication, CAS races, cancellation, denial, tampering
and key loss. Eight captured-reference cases cover company selection, old ownership,
late failures, exact local resolution and disposal. Actual DI is resolved as well.

The incoming app coordinator still needs to use this progress under bounded native
ownership, independently of outgoing backlog. Recent catchup requires its own durable
traversal, so repeated background wakes do not reset the user's older-history cursor
or mistake a latest 128-event window for complete offline recovery. Actual provider,
FCM receive, notification privacy/actions and encrypted inline replies remain separate
acceptance work. This increment installs no employee APK and activates no server push.
