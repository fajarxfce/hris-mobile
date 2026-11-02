# Preparing a chat notification

`PrepareCollaborationMessageNotification` prepares one local notification candidate
from acquired, encrypted original history. Its caller supplies captured account
ownership and an exact conversation/room/event key. A push hint is scheduling
metadata and never supplies a sender, message body or display authorization.

The operation requires the retained direct-conversation binding and the account's
protected Matrix identity. It validates the original and its aliases, then suppresses
muted conversations, self-sent messages, redacted originals and logical messages with
any locally read alias. A confirmed tombstone also suppresses when the original has
not been acquired. Missing history or identity remains pending. Storage failure or
malformed data never becomes a generic successful alert.

Preview defaults hide the sender and body. Sender-only mode exposes the retained
counterpart label; message mode also exposes a bounded text preview. Sender and body
limits are160 and400 UTF-16 code units; a scan reads at most4096 code units, drops
control characters except newline/tab and avoids a trailing split surrogate pair.
The source remains untouched. Diagnostic representations redact notification content.
The counterpart label is scoped to the currently supported HRIS-managed direct room;
group author labels and edit/media projections need their own implementations.

Local ownership and retained denial are checked after every pending source read.
Ordinary token age and the absence of a selected workspace do not hide owned facts.
The operation has a ten-second budget and propagates caller cancellation. It never
enrolls, joins, starts an SDK, requests network data, marks a message read, acknowledges
delivery or invokes the operating system.

The original's bounded alias list is copied before further suspension. Before
returning content, the operation rechecks the original and then the complete settings
snapshot after read-state acquisition. A changed original or preference returns an
explicit conflict without hidden retry. Read coverage arriving during the initial
history lookup suppresses the message. The candidate carries its account binding,
canonical original/content hash and observed settings/read revisions.

Those revisions are source stamps, not an atomic lease over native display. A later
read or privacy change can happen after preparation. The delivery coordinator must
own durable display/action state, revalidate at delivery, serialize display/cancel
work, and remove stale notifications on read, mute, redaction or account changes.
A suppressed query is not a blanket instruction to cancel an unrelated existing
card: re-evaluate that card using its own persisted canonical original. Stable native
IDs must make uncertain display acknowledgment recoverable without duplicate alerts. This local preparation alone does not provide that integration.

The encrypted delivery/action ledger, backlog policy, foreground suppression, UI
controls, native display, lock-screen public versions, deep links and inline replies
remain pending. In particular, Android PendingIntent reuse does not provide a unique
broadcast-delivery ID: reply actions need a durable account-bound token and defined
consumption/rotation behavior. Live chat push stays disabled until the complete path
is implemented and verified.

[Validation](measurements/chat-notification-projection-2026-11-02.json): final focused
13 cases (11 domain/two actual DI) pass137.768s. Full root/proof/Wasm/browser
passes333.788s:3346 desktop,230 Android host,10 build-logic, six proof-host and11
browser cases including explicit task reuse;1778 cases/228 suites fresh, no
failures/errors/skips. The earlier10-case focused pass precedes the reviewed
tombstone/final-privacy corrections and three added regressions. No native OS
notification delivery is claimed by these local checks.
