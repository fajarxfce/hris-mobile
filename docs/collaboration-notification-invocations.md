# Native notification command dispatch

`OpenCollaborationNotificationAction` reads the current native parent from session
storage and opens the encrypted token against that family. It takes no account,
company or operation kind from outer native extras. A retained company denial and
current local ownership surround pending operations. The acquired direct conversation
must match its company/account/peer. Reply and read require the exact retained event
fact; a valid tombstone remains a fact. Mute and dismissal do not require the original
body to remain available. No network or selected workspace is involved, and ordinary
credential expiry does not erase local ownership.

The returned captured scope also pins the remote native session. Existing message,
read and settings use cases check that pin throughout their pending local work.
Replacement of that remote family rejects even if the local session/account IDs
are unchanged. Normal captured reads omit the optional remote-session pin and still
check their captured local session/account. Observers with a pin close on family replacement; access
token rotation alone keeps them open.

The app's singleton `CollaborationNotificationCommands` dispatches one operation
within an eight-second budget, rejecting overlap instead of retaining a waiting
queue. The opening use case has its own ten-second maximum; caller cancellation
and the shorter app deadline join pending children. Authenticated metadata selects
the operation. Reply input on another kind, missing reply text and invalid text
reject before any command is prepared.

- Reply uses the envelope's stable operation UUID through the existing encrypted
  message command. Same-input retry reuses a queued or archived record; changed
  text with the old operation rejects. The result retains its actual local message
  state, including an already sent or cancelled record. It does not imply delivery.
- Read covers the exact target event and uses a private receipt. Its `onlyIfUnread`
  policy skips fully retained coverage even after a later acknowledgment replaces
  the old receipt ID. A known operation with different contents still conflicts.
  Other callers keep the normal explicit receipt policy. Recovery scheduling is
  best effort after durable storage and never acknowledges provider delivery.
- Mute sets forever with the captured preview and expected settings revision. A
  replay may recover the same change, but cannot overwrite a subsequent unmute or
  other preference change.
- Dismiss returns the authenticated original card/render request. It does not mark
  the conversation read or cancel a native card by itself.

These results remain captured local command outcomes, not OS display leases. Native
publication and cancellation must match the exact render and current owner. Denial
or unavailable history must not prevent a separate native privacy cleanup path from
removing an already displayed card.

The Android receiver, RemoteInput plumbing, durable issuance/display journal, native
publication/cancellation and failed-input storage are still required. Before invoking
the reply path, the native owner must retain its input separately from the composer;
on failure, queue capacity or uncertainty it must keep that input for recovery.
This dispatcher does not provide that storage and is not yet registered as an OS
callback. Reuse the exact persisted token after uncertainty; sealing again creates
different ciphertext. No live chat push flag is enabled here.
