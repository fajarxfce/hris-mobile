# Local message editor

Status: accepted locally by full root, browser, Android build and lint gates.
No native encrypted edit exchange or release installation is accepted yet.

Long-press an owned, visible, sent text message and choose Edit from its action
menu. The local candidate must still be inside the original edit window. Entry
captures its authenticated room, sender, original event/logical/content identity
and reply/forward context. Save rechecks local ownership and target availability.
The UI offers no always-visible selection action, network refresh or send spinner.

Editing has its own route-owned buffer. Ordinary message text and quoted reply
remain intact through edit/save/cancel/back. A lost local write response retains
the same immutable operation and text for retry, even after the authoring window
expires. Locked text remains selectable for copying. Stopping the route cancels
its editor work; account replacement invalidates it. No new provider request is
needed for local preparation or outcome reads.

A queued edit changes the displayed text of the original bubble, preserving its
key, timestamp and original delivery/read reference. Replacement transport progress
is separate from original delivery. Typed/native fallback edit echoes never become
extra bubbles. Only commands matching the authenticated history room and original
identity can be projected. An older unresolved edit cannot cover a newer observed
or retired local edit. Provider evidence suppresses that operation's optimistic
text; an ineligible edit restores accepted text and retains its failure.

Outcome reads use bounded, exact body-free evidence and immutable completion
records. Local history pages carry an opaque revision token so rejected/redacted
edits invalidate outcomes even when the original message text does not change.
Replaying identical evidence leaves that revision unchanged. Ordinary credential
age does not hide owned history or queued changes.

Discard change acknowledges only the operation IDs the user reviewed, tied to
captured account access, conversation, room and original event. It archives through
the existing local retirement flow; it never resends a rejected replacement or
hides a concurrently arriving failure. Late access denial and cancellation retain
unacknowledged commands. Original receipts and replacement receipts stay distinct.

Validation covers real SQLite/IndexedDB replay/reopen invalidation,
late denial, cancellation, exact acknowledgement IDs, editor stop/retry/account
replacement and normal draft preservation. Visual evidence uses compact light,
dark with doubled text and landscape under reduced motion. These screenshots are
an interaction/layout check, not Android native transport or Apple acceptance.

Inspected desktop captures: [compact light](images/chat-editor/chat-editor-light.png),
[dark doubled text](images/chat-editor/chat-editor-dark-large-text.png),
[landscape](images/chat-editor/chat-editor-landscape.png). Edit/cancel/save targets
remain visible with the original bubble; route/transport acceptance stays separate.


[Acceptance evidence](measurements/collaboration-edit-editor-2026-11-06.json) records
33 Kotlin/two resource files,68 focused presentation cases and25 regression cases.
Final full2 PASS2,347 fresh/378.927s, zero failures/errors/skips, Android lint0;
4,188 source inputs unchanged. Other Gradle checks/reports remained up-to-date.
Failed attempts remain recorded, including first full5,406 cases/three failures.
The corrected deadline fixture drives the actual worker timer to1,999ms/2,000ms
and retains its cancellation/close/no-publication assertions. History storage now
asserts both stable page contents and deliberate revision invalidation/reopen.
Before phone release, finish raw encrypted history proof and actual ciphertext size.
The follow-up recovery now offers Copy unapplied changes in normal long-press
actions, preserves all attempted texts/drafts and rechecks owner/viewport before
clipboard access. See [recovery evidence](measurements/collaboration-room-preparation-2026-11-06.json).
No generic starter foundation change was needed for this HRIS feature increment.
