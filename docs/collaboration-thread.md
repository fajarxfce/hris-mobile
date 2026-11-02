# Owned message thread

The Chat service opens the inbox under the selected company's collaboration module
and `collaboration.chat` permission. Inbox, participant selection, setup and thread
routes own their ViewModels and lifecycle effects. Selecting a participant replaces
that picker destination; device activation returns to the original thread. Routes
carry a conversation ID, never a message draft or provider credential.

## Local content and pending sends

`ReadCollaborationThreadContext` reads an owned cached conversation and cached sender
identity without network access or an SDK runtime. Company admission and ownership
are checked around every read. Ordinary credential age does not hide this context,
canonical history or durable pending messages. Account/company loss clears visible
and hidden thread content, the volatile draft, reserved send identity and native child.
Local observers remain owned by the destination until its ViewModel is cleared.

History uses up to ten live local pages of fifty records. Overlapping pages are
deduplicated by stable primary event identity. This pagination reads acquired local
history; it does not claim the provider's complete history has been downloaded.

Sending first calls `QueueCollaborationText` with an application UUID and exact text.
The composer reserves that UUID/text before storage. A failed or cancelled response
cannot prove storage failed: editing is held until retry reads or stores the same
intent. A successful local acknowledgement clears the composer. No message plaintext
is put in navigation arguments or SavedStateHandle. Drafts are currently volatile;
durable draft storage and explicit pending-message cancellation remain separate work.

## Projection and identity

The page composes three sources: canonical original history, owned durable commands,
and a volatile SDK timeline. Provider logical IDs alone cannot merge rows. Canonical
pending matches require retained original sender, content hash and original text;
confirmed redactions suppress body resurrection. Native local echoes match only the
exact persisted native family/device/bootstrap/room, sender, transaction and logical
ID. Unknown SDK local echoes are omitted; durable owned commands supply local rows.
`EnterCollaborationConversation` validates the timeline child's complete source before
publication and retains received company denials even across cancellation.

Own outgoing keys remain stable from local queue through SDK confirmation, history
acquisition and retirement. Verified aliases suppress duplicate events. Native edited
presentation may overlay an original only for the same event alias and sender; this
never rewrites the canonical original. Unknown sender identity is explicit in the UI.
Projection itself never acknowledges, retires, deletes or re-enqueues a command.

## Foreground connection

Entry reads setup and restores an existing application-owned runtime. Missing setup
is an explicit activation action; local reading and owned queueing remain available.
A ready thread owns only its child timeline and a conflated history-acquisition channel.
Stop cancels screen work and closes late children; parent runtime and delivery belong to
the application. Local observers continue clearing hidden content after ownership loss.

Sending persists local intent and schedules the application worker. The screen never
stages or resumes a native send queue, reconciles receipts or retires commands. Timeline
updates acquire originals for the owned projection and may wake pending delivery. Explicit
refresh also requests delivery. A failed room retry persists explicit control intent and
wakes the worker; opening the screen does not silently retry failed provisioning. Readiness
polling uses at most twelve reads with two-second spacing.

[Application delivery](collaboration-background-delivery.md) owns exact room-operation
replay, bounded message batches, original-evidence recovery and completion retirement.
An empty native queue/history result is never an acknowledgement.

If incoming history reaches native focus capacity, explicit Refresh releases the old
child, performs an admitted latest-head capacity reopening, and acquires a new child.
Owned local content stays visible. Cancellation cannot reopen a late replacement child;
this finite action never starts native message delivery. See [recovery](collaboration-history-recovery.md).

## Interface and evidence

The compact composer stays below a reversed message list, respects safe-area/keyboard
insets and preserves multiline input. Back/refresh/send controls use at least 48 dp.
The layout is bounded to 720 dp on wide screens. Short conversations sit above the
composer. Text selection is available, and deleted/undecryptable rows cannot render
an accidental raw body. Message times use the device timezone and selected language.
Desktop layout tests capture fictional [English](images/chat-thread/chat-thread-english.png)
and [Indonesian 200% text](images/chat-thread/chat-thread-indonesian-large-text.png) examples.
The screenshots were opened for visual review; they are not physical-device evidence.

The original thread [validation](measurements/chat-thread-2026-11-01.json) binds 30 matching source inputs
and all six attempts. Focused checks pass in 88 seconds and the full root/browser/Android
checks pass in 232 seconds: 2,660 desktop tests, 332 domain browser, 55 presentation browser
and 170 Android host tests, with zero failures/errors/skips. Android lint has zero errors
and one existing warning. The dev APK, presentation AAR and proof APK are built; no new
actual-server/emulator or Apple runtime run is claimed.
No full PRD feature group is accepted by the thread alone. Remaining work includes
native background-worker interoperability, remote history pagination, durable drafts,
groups, verification and key migration, search, notifications/inline reply, richer
message types/actions, Apple/web SDK adapters, calls and meetings. Application scheduling
alone does not establish all WhatsApp-style background acceptance criteria.

The resumed, focused viewport now marks exact acquired colleague originals as locally
read and retains private receipt intent before requesting worker recovery. Offline
reopen uses the authenticated history room binding. Hidden rows, dialogs and stopped
windows do not mark. No peer read/delivery tick is inferred. See [read-state contract](collaboration-read-state.md)
and [viewport/lifecycle evidence](measurements/chat-visible-read-2026-11-02.json).
