# Offline chat list previews

The primary Chat tab reads its conversation names before acquiring optional
message and draft details. `ObserveCollaborationInbox` coordinates only local
repositories. It observes the same account/company admission gate as the saved
list; it never creates an SDK runtime, fetches a provider room or starts delivery.

Each visible page contains at most 50 conversations (30 in the current UI). Only
that page owns detail observers: one latest-message query with limit 1 and one
composer-slot observation per conversation. A single shared account command observer
reads at most128 original sends for the entire visible page. The history repository authenticates
its bounded lookahead as before. Changing page cancels those children. Hiding the
tab stops detail observers while retaining the list/admission observation, so a
hidden route still clears after confirmed denial or account replacement.

Names and details publish as one owned page. Revalidation after local acquisition
withholds late previews when ownership or admission changes. Corrupt optional
content clears previews and exposes a recoverable failure without removing saved
conversation names; ownership failure clears the entire page. Cancellation waits
for an uncooperative child read before allowing its owner to finish.

The row also projects the latest original local send before transport acknowledges it.
An unsent command shows its text and a compact clock icon; an acknowledged retained
command shows a check. An exact corresponding history row takes over without restoring
the earlier text. Cancelled sends, edits of earlier messages and other conversations
do not replace the preview. A nonempty editable draft keeps its Draf/Draft label.
A frozen composer is already a send action and shows a clock until its exact command
is visible; the same submitted text is not relabelled as an editable draft.
Redacted originals use the deleted-message label. Same-day timestamps use HH:mm,
yesterday and the preceding weekdays use localized labels, and older dates use
dd/MM/yyyy. Draft dates use the actual saved timestamp. At large text sizes a
narrow heading moves its time below the name instead of squeezing the name beside
a full date. Preview text can wrap to two lines; opening the row shows the thread.

This projection does not infer unread counts, recipient acknowledgements or global
recency ordering. The existing acquired conversation cursor is preserved; it is
not a last-activity cursor. Delivery/read ACKs, an indexed recent-conversation
projection and group ownership across company selection remain required work.
No generic Starter component changes are included.

## Pending-preview acceptance — 7 November 2026

The eleven-file change passed focused74307:50 cases/7 suites/266.430s, and full95262:
3,760 fresh cases/458 suites/514.213s. Both used4,412 unchanged inputs and had zero
failures, errors or skips. Root check, Android host/release lint, formatting,
architecture, Detekt, shared Wasm compilation and20 explicitly verified Browser
cases passed. Both jobs are reaped. [Exact source and reports](measurements/chat-inbox-pending-2026-11-07.json).

Regression cases cover offline pending updates with expired credentials, hidden
reopening, one queue subscription for30 visible conversations, no blank emission
for unchanged inputs, frozen-draft handoff, original send order across ACK/history
publication, account boundaries, denial/disposal, bounded immutable snapshots,
redaction and edits. No sending, SDK acquisition or API request is added to observation.

The three current synthetic captures were opened and reviewed: [small phone](images/chat-inbox-pending/chat-inbox-previews-light.png),
[dark with2x text](images/chat-inbox-pending/chat-inbox-previews-dark-large-text.png),
and [landscape](images/chat-inbox-pending/chat-inbox-previews-landscape.png).
These are rendered UI checks, not pixel-baseline comparisons. First source700 CI is
now artifact-audited: Apple2,152 cases, portable5,171, and553 native fixture cases per
Android ABI, all zero failures/errors/skips. [Exact first CI](measurements/chat-preview-first-ci-2026-11-07.json).

Release55 on source95e35b9d passes actual original-store emulator acceptance: pending
and draft previews survive cold offline restart; the draft restores to its composer.
The same original offline send becomes Sent after reconnect without a foreground
rescue. Both online send and reconnect showed one visible outgoing row in coarse
observations. Four screenshots and the first6s of both videos at4fps were inspected.
The exact validated ARM64 APK is installed in place on the authorized phone.
[Device/build evidence](measurements/collaboration-release55-2026-11-07.json).
Keyboard dismissal, first-frame clipping and final phone latency remain open.

## Initial preview acceptance — 3 November 2026

The initial focused check passed 1,101 cases. After correcting the large-text
name/date collision and local retry behavior, the complete check passed in
672.455 seconds with unchanged source: 4,037 desktop cases, 230 Android host,
18 proof-host and ten build-logic cases (including reused reports). Of these,
1,805 cases across 259 suites executed fresh. Format, architecture, detekt,
Wasm compilation and both Android builds passed. Product lint has zero issues;
proof lint retains 47 existing warnings and zero errors.

Seven new domain cases cover expiry, slow reads, cancellation, late account
replacement, denial/reopening, corrupt content and invalid page/draft boundaries.
Three added VM cases cover offline draft updates, page/visibility ownership and
explicit retry after a completed local failure while the network stays offline.
Three layout cases capture light, dark with 2× text and landscape screenshots;
all final PNGs were visually inspected. Large text verifies that the complete
contact name fits after scrolling, and opening a row still emits its typed action.

See [measurement](measurements/chat-inbox-previews-2026-11-03.json),
[light](images/chat-inbox/chat-inbox-previews-light.png),
[dark large text](images/chat-inbox/chat-inbox-previews-dark-large-text.png) and
[landscape](images/chat-inbox/chat-inbox-previews-landscape.png).
