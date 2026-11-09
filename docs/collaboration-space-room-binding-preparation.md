# Owned room binding preparation

`PrepareCollaborationSpaceRoomBinding` is a candidate owning use case for resolving
published room metadata. It coordinates repositories and pure policies; it does not
invoke another use case, open an SDK, request a provider room, enqueue messages, change
keys or reopen content admission. No UI or worker caller is activated yet.

The first operation is an exact owned local binding read. Account/API/session ownership
is checked before and after storage. A matching complete binding returns without native
expiry validation, directory acquisition, metadata writes or network work. Invalid or
contradictory local data fails without replacing the record or falling back to HTTP.
The separate `readOwnedBinding` operation keeps this local contract inside the use case.

Only absence enters current native acquisition. One published-room read must match the
captured canonical conversation/owner. A missing publication returns not-ready without
requesting, polling or retrying room setup. SPACE performs no original read; LEGACY
performs one exact original company/conversation read and validates owner, pair, room,
server and binding time before retaining any new fact. There is no directory scan or
selected-company fallback.

The owning use case checks native identity and directory admission after each suspended
live operation. A confirmed denial survives a later storage/unavailable failure and
bounded restriction cleanup. The original native deadline is retained even if credentials
renew; wall rollback and a15-second monotonic budget cannot extend acquisition. Cancellation
propagates. Current content admission is never reopened by successful metadata recovery.

Canonical metadata is matched before the room binding is retained. Each namespace has
its own existing atomic transaction. A saved canonical record remains safe if the later
binding step is interrupted. The use case reads back the exact canonical/binding facts
after an uncertain local response, without issuing another write. A missing or conflicting
readback cannot become success. A committed binding interrupted by cancellation is available
through the local path next time, including after login replacement and native expiry.

## Verification in progress

The initial focused gate71916 ran20 domain cases successfully but FAILED Detekt's
LongMethod rule (invoke105 logical lines, maximum100). This was not accepted. The local
owned-read operation has since been separated from fresh acquisition in the same use
case, preserving its responsibilities and checks; no suppression was added.

Three shared actual-database cases now cover both uncertain publications, a cancelled
committed binding followed by database/login reopening, and a persisted directory denial
after binding publication. Desktop SQLite, browser IndexedDB and Apple SQLite wrappers
share that contract. The actual application resolves the new use case through feature DI.
Focused92982 is reaped PASS239.944s:25 cases (20 domain,3 actual SQLite,2 app DI).
Full36351 is reaped PASS586.604s:4,222 fresh cases (3,586 desktop,613 Android host,
23 actual browser), no failures/errors/skips. Browser XML confirms the exact20 domain
and3 IndexedDB cases; all4,551 app/library inputs are unchanged. This source also
passes root/release/Wasm/iOS-source and quality checks.

A subsequent review found a coincident-expiry race. The directory returned a valid
confirmed denial, but the native session check returned first, replacing that denial.
Regression10100 fails once on the unchanged production source: AccessDenied becomes
client.session_changed. The correction retains that captured denial before checking
native expiry. No local ownership or content reopening behavior changes.
Formatter63867 passes. Corrected focused61497 is running with21 domain,3 database and
2 app cases; the final full gate must also execute24 actual browser cases. Earlier full
proof is retained separately and does not validate this correction. No new Apple
execution, APK, SDK adoption or product activation is claimed.
