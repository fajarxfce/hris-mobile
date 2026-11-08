# Chat input and local submission

Pressing Send captures one immutable original, displays its pending bubble, and clears
only the text that was submitted. The field stays enabled and retains focus. A later
keystroke belongs to the next editor revision, so completing the first write cannot
replace newer text. The route scrolls to the new message independently of delivery.

The existing [atomic handoff](collaboration-composer-handoff.md) stores the original
and advances the expected editor revision in one encrypted local transaction.
The delivery worker materializes and sends that same original. A local commit is not
an acknowledgement from Matrix. Read receipts and server acknowledgement retain their
existing meanings; no synthetic command or Sent record is introduced for presentation.

## Ownership

- `ChatComposerEditingSession` belongs to the route's ViewModel scope and UI dispatcher.
  It serializes captured editor/send attempts, debounces ordinary typing, keeps admitted
  writes alive when the route hides, and cancels/fences them on account replacement or
  disposal. Retry retains the captured ID, timestamp, body and reply target.
- `ChatThreadViewModel` handles typed events, calls use cases and updates screen state.
  It does not create a room, perform SDK I/O, or materialize a message from the UI event.
  A locally materialized command can wake an already waiting room connection.
- `SubmitCollaborationComposer` owns exact handoff admission and replay. The existing
  worker calls `RecoverCollaborationComposer`; explicit retry revisits only the selected
  retained original, including an original held for review, without advancing its cursor.
- `ObserveCollaborationComposerProgress` owns local access and admission, exact immutable
  intent matching, and account/lifecycle cancellation. It combines the bounded outbox
  with exact active/archive observations. It never polls or acquires network content.
- `CancelCollaborationComposer` requires authenticated exact cancellation proof before
  removing the original. An absent record can be prevented; work already started or
  acknowledged cannot be represented as cancelled. UI discard is limited to a gesture
  that has never been passed to submission.
- The encrypted message repository maps the raw observer, rejects contradictory or
  mismatched records, and does not create an encryption key during a read.
  Room and IndexedDB read account ownership, active work and completion archive in one
  transaction, including completions that predate the subscription.

## Continuity and bounds

The thread merges provisional originals with real command/history facts by the stable
outgoing ID. The original gesture timestamp preserves order when acknowledgement arrives
out of order. Short route-owned watches bridge independently delivered outbox and command
snapshots, including cold restore. Visibility can release provisional UI memory; it grants
no durable retirement authority.

Inbox observes retained originals as well as ordinary previews. An editable draft takes
precedence; otherwise the latest submitted original appears while pending. A cancelled
original disappears only after exact record evidence. A held original stays available for
explicit retry or cancellation. Old-account previews are cleared on owner replacement.

Existing bounds remain explicit: 128 retained originals, up to 128 additional requested
IDs in progress observation, at most 16 not-yet-completed local UI sends, and the existing
bounded thread/list windows. Hitting a local bound does not replace or drop an original.
Route continuity watches select the latest bounded window while current gestures remain
watched. This increment does not implement unbounded offline history or WhatsApp parity.

Legacy frozen drafts remain recoverable by the worker. They are not transformed into a
new send gesture by re-entering the room. No existing keys, SDK transaction IDs, message
archives or application data are reset by this change.

## Validation

[Recorded checks](measurements/collaboration-composer-input-2026-11-08.json) retain both
failed attempts and successful results with source/report fingerprints. Tests exercise
consecutive gestures during suspended local writes, cold restore, late results, cancellation,
held-original retry, scope replacement, encrypted exact/archive reads, preview continuity,
and actual ViewModel-to-Compose input focus. Browser checks run against actual IndexedDB;
Apple fixtures are source coverage until run on an Apple host.

Signed Android device acceptance and physical phone installation are separate gates.
Release65 remains the installed baseline until a new artifact passes those gates.
