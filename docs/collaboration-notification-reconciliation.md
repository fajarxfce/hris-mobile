# Finite notification reconciliation

The application now reconciles an owned publication reference against its retained
source, using the same admitted publisher and exact native owner. The Koin factory
receives that already-started owner. It does not start a process host, subscribe to
source changes, authenticate native callbacks, or acquire messages from the provider.
Its reference must come from owned journal discovery, never untrusted intent extras.

IssueCollaborationMessageNotification accepts an optional expected publication
reference. It validates the condition's shape/account/company/conversation before
journal IO, and compares the internally read reference before reusing or replacing it.
A changed or missing reference returns Unchanged without sealing or mutation. Normal
issuance keeps its existing behavior; CAS and post-IO validation still apply. The
publisher forwards this condition. Unchanged becomes Contended in reconciliation,
with no native removal. This includes an acknowledgment-only version change whose
native render ID stayed the same: a stale cleanup request cannot clear that valid card.

For a current admitted source, reconciliation rerenders silently. An observed source
failure bypasses publication entirely, even if a separate finite content read could
succeed. Confirmed read/mute/redaction/own-message suppression or company denial calls
exact native removal, then exact journal cancellation. The native obligation is thus
registered before journal IO: cancellation or a lost committed journal response still
joins cleanup. A newer native render returns Contended; it is never removed through
the old reference. Journal contention also requires discovering the current reference.

Transient, permission, channel, capacity or missing-history outcomes hide the exact
native card while preserving its active journal for explicit recovery. Submitted means
native submission was acknowledged through the publisher, Retired means exact native
removal and journal retirement completed, Hidden preserves the journal, and Contended
requires rescan. A native failure is propagated; repeated compensation failure keeps
the owner blocked until explicit successful cleanup. The two finite owner turns avoid
nested ownership locks and retain exact-reference checks across their boundary.

Three new portable cases supplement the seventeen existing issuer cases. Nine actual
Koin/SQLite integration cases cover privacy, mute/private reads across reopen, stale
acknowledgments/renders, watcher failure and silent recovery, late denial, committed
response loss/cancellation, repeated native removal failure, and foreign/inactive
references. The fixture uses fictional acquisition and a fake OS. These tests do not
activate HRIS notifications or prove notification-input OS process-death recovery.

The next process owner must serialize bounded opaque candidate/dirty work, register and
join source/focus observers, revalidate initial subscriptions, rescan after journal
changes, and handle Busy/Contended outcomes without dropping work or retrying in a hot
loop. Callback authentication and retained-input review remain required before enabling
chat push. See [recorded checks](measurements/chat-notification-reconciliation-2026-11-03.json)
and the [remaining host plan](collaboration-notification-publication-plan.md).


[The bounded process coordinator](collaboration-notification-process.md) now owns active
journal/source reconciliation and exact cleanup of obsolete references. It retains dirty
work through contention without repeating its own ACK, and pauses on uncertain discovery.
Candidate delivery, owned foreground focus, authenticated callbacks and encrypted input
review still precede native HRIS activation. No chat push is enabled by this binding.
