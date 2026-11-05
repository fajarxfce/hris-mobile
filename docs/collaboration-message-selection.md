# Chat selection and deletion on this device

Long-press a bubble to select it, then tap more bubbles. The contextual toolbar
shows the count, copy and eligible deletion/cancellation actions. The normal toolbar
also provides selection entry, and every bubble exposes an accessible select action.
Back dismisses an open confirmation before clearing selection, then uses the existing
draft-aware route departure. Selecting messages hides the keyboard and disables the
composer without discarding its durable draft. Up to 50 message snapshots survive
local pagination; current echoes reconcile by stable key or sender/event/logical
identity. Account replacement or known access denial clears content and selections.

Copy orders selected text chronologically and uses the platform clipboard from the
route. The effect captures the local owner and ephemeral thread generation; fresh
thread-context ownership must still match immediately before clipboard I/O. Route
disposal owns cancellation. Redacted/unsupported content is not copied as fabricated
text. Queued messages keep their existing explicit single-command cancellation;
local hiding never promises that a pending/uncertain send will not reach the server.

Delete for me confirms the selected count and clearly states that other participants
retain the messages. The exact captured references survive a lost commit response,
and retry applies the same idempotent local mutation. A dialog can be dismissed even
while storage is pending; cancellation does not undo an already committed fact.
The owning use case validates local account/company/conversation admission, allows
ordinary credential age, bounds all work to 15 seconds, retries one checkpoint race
and checks ownership again before publication. No HTTP/SDK mutation occurs.

History retains authenticated originals, provider event aliases and redaction facts
for send/receipt reconciliation. Local visibility is an independent encrypted choice
inside the same account/company/conversation partition and checkpoint. HMAC indexes
bind sender plus event or logical ID; no plaintext sender, event ID or body appears
in indexes. Hidden original rows use a separate authenticated tag, so indexed local
history pages and inbox previews skip them without scanning every hidden message.
Original counts and cursor ordering remain valid. Later original imports, provider
retries and redactions preserve the local choice. A selection can also be hidden
before history import; unmatched SDK/sent echoes pass bounded local visibility reads
before appearing. A retained sent command supplies its original room when offline
history has not yet been imported; this does not reopen an SDK or grant live access.

One local batch contains at most 50 distinct references and at most 150 changed
documents/rules, under the raw storage batch limit of 200. A visibility read checks
at most 256 distinct live references. Checkpoint CAS publishes changed messages and
rules atomically. Repeated deletion is a no-op, preserving the checkpoint. Wrong
sender/logical identity, scope/room, altered encrypted documents and missing keys
fail closed. Key loss never regenerates over retained history or visibility records.

Validation must cover real SQLite and IndexedDB, actual application DI, pending
cancellation, lost local responses, late ownership changes and local/live replay.
Plain Compose state tests cover long-press/tap, selected semantics, copy/delete
callbacks, 48dp controls and large-text dialog escape. Review generated light/dark,
375px/landscape/large-text screenshots; do not infer visual acceptance from test pass.

Remaining: durable delivered-message editing, quoted reply, forwarding with recipient
selection, delete for everyone and their operation-specific offline reconciliation.
Existing original history intentionally rejects m.replace as an original; durable
edits must add an authenticated replacement projection without overwriting original
send evidence. This slice does not claim full WhatsApp/Telegram, group migration,
voice/video/meeting, notifications or Apple runtime acceptance. All 54 PRD groups
remain open. Product delivery needs the next validated APK; release30 predates this UI.

## Accepted checks

Focused 112 cases / 99.232 s; root :check, Android-host and dev compilation 3,058 fresh cases / 384.05 s; browser data/domain 1,654 fresh cases / 168.16 s. All pass on identical 4,033 build/application inputs; format, architecture and detekt pass. Four generated screenshots were opened and reviewed, including 200% text and a 320px confirmation. [Evidence](measurements/chat-message-selection-2026-11-05.json).
