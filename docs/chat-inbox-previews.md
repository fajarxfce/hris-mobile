# Offline chat list previews

The primary Chat tab reads its conversation names before acquiring optional
message and draft details. `ObserveCollaborationInbox` coordinates only local
repositories. It observes the same account/company admission gate as the saved
list; it never creates an SDK runtime, fetches a provider room or starts delivery.

Each visible page contains at most 50 conversations (30 in the current UI). Only
that page owns detail observers: one latest-message query with limit 1 and one
composer-slot observation per conversation. The history repository authenticates
its bounded lookahead as before. Changing page cancels those children. Hiding the
tab stops detail observers while retaining the list/admission observation, so a
hidden route still clears after confirmed denial or account replacement.

Names and details publish as one owned page. Revalidation after local acquisition
withholds late previews when ownership or admission changes. Corrupt optional
content clears previews and exposes a recoverable failure without removing saved
conversation names; ownership failure clears the entire page. Cancellation waits
for an uncooperative child read before allowing its owner to finish.

The row shows the original retained message, or a labelled nonempty composer draft.
Redacted originals use the deleted-message label. Same-day timestamps use HH:mm,
yesterday and the preceding weekdays use localized labels, and older dates use
dd/MM/yyyy. Draft dates use the actual saved timestamp. At large text sizes a
narrow heading moves its time below the name instead of squeezing the name beside
a full date. Preview text can wrap to two lines; opening the row shows the thread.

This increment does not infer unread counts, recipient acknowledgements or global
recency ordering. The existing acquired conversation cursor is preserved; it is
not a last-activity cursor. Delivery/read ACKs, an indexed recent-conversation
projection and group ownership across company selection remain required work.
No generic Starter component changes are included.

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
