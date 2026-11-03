# Joined cleanup after explicit notification removal

The surface lease now records an explicit removal's exact opaque reference before
calling the native adapter. Removal shares the existing four-reference bound with
submission. This closes a gap where a failed remove-only request could leave the
owner Ready because its compensation list contained no attempted submissions.

On a failed or cancelled request, the owner first closes the lease and joins pending
work, then compensates those exact references while holding global serialization.
A successful compensation permits later turns. If cleanup also fails, the owner stays
Blocked until explicit namespace reconciliation succeeds. The first operation still
returns its failure; compensation does not turn uncertain work into reported success.

An old reference never authorizes removal of a newer render. The adapter's existing
render checks remain authoritative, including during compensation. Cancellation joins
cleanup before the cancelled caller returns. The bounded list contains only opaque
references, and neither this change nor cleanup stores message previews or reply text.

Five new cases extend the mechanical fake-OS/virtual-time owner suite to 22 tests:
first-removal failure with successful compensation, repeated failure and explicit
recovery, pending cancellation with joined cleanup, preservation of a newer render,
and the shared four-reference bound. The raw native adapter itself is unchanged.
No new native IPC, user-phone interaction or notification-input process-death proof
is claimed by these tests.

This is part of native ownership, not HRIS source admission or an activated notification
host. Finite semantic reconciliation and the process lifetime still must connect
source changes, exact journal settlement and native callbacks. See [validation](measurements/chat-notification-removal-2026-11-03.json)
and the [host integration plan](collaboration-notification-publication-plan.md).
