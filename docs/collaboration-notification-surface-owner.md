# Application notification surface ownership

`CollaborationNotificationSurfaceOwner` serializes native requests for a supplied native
parent. It is a mechanical application lifetime and is not bound into HRIS delivery yet.
Actual session/company/source admission belongs to the future coordinator, inside the
owned request and immediately before native publication and journal settlement.

Startup closes the gate until namespace cleanup finishes. Parent changes close the
current epoch first, cancel and join active work, then serialize cleanup. An epoch can
open only if the latest parent still matches. A cleanup failure leaves the gate blocked;
`reconcile` performs one explicit finite retry, with an exact phase comparison so it
cannot reopen an epoch replaced during its pending I/O. Logout can clear native state
without opening a publishing epoch.

Requests use a nonwaiting mutex and a ten-second cooperative deadline. A second caller
does not wait with private input. Each lease permits at most four attempted opaque card/
render references, records them before submission and serializes its native calls.
A coroutine-context marker, current epoch, latest parent and completion flag keep a
retained lease from operating after its request or outside its owned execution.
Concurrent child native calls reject an extra waiter.

On failed, cancelled or uncertain work, the owner closes the lease, joins its children
and attempts exact-render compensation while retaining serialization. Compensation and
namespace cleanup each have a two-second cooperative budget. Failed compensation closes
the gate until reconciliation. `closeAndJoin` also joins externally initiated work and
observer cleanup. No Activity, preview text, action envelope or queued private payload
is retained in the long-lived tracking state. Deadlines require cooperating I/O; joining
a delayed native operation does not make OS Binder and database state atomic.

## Validation and remaining boundary

Seventeen common tests use virtual time and a fake native surface. They cover startup,
logout, replacement before observer consumption, pending and non-cancellable late I/O,
caller/deadline cancellation, missing cleanup acknowledgments, failed reconciliation,
concurrent requests/children, leaked leases and orderly closure. They run on desktop and
actual browser runtimes. This is not actual native integration or an application DI
binding test. [Evidence](measurements/chat-notification-surface-owner-2026-11-02.json)
records the corrected test-only generic inference failure, explicit reuse and root checks.

The raw Android surface currently enumerates active OS cards for namespace cleanup,
while `notify` acknowledges a queued request. Add queued-submission and restart coverage
before claiming cleanup of every accepted request; durable opaque pending IDs independent
of account decryption may be needed. The earlier eleven-group raw Android proof does not
exercise this owner or prove notification-input process death.

The coordinator still needs all-company admission, settings/read/history invalidation,
focused-thread registration, bounded opaque journal discovery and retirement, conservative
alert/replay policy, routing and explicit encrypted review after pre-retention/old-family
reply errors. Existing action opening gates company access and is insufficient by itself
for denied-card cleanup. The integration plan tracks these separately. Current Apple
41547382869 atdb74e22d excludes this owner. Chat push remains off; the phone remains on
release19. Generic Starter port follows integrated Mobile proof.
