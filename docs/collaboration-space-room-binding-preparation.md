# Owned room binding preparation

`PrepareCollaborationSpaceRoomBinding` is an owning use case for resolving
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

## Verification

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
The first correction still failed focused61497 (26 cases, one failure): the general
response-precedence policy classified both the stored denial and local session expiry
as directory-closing, so the later local failure still replaced the denial. The final
correction preserves a captured global denial specifically at local native validation;
a prior network error still yields a later native rejection. No global response policy
was weakened. The expanded regression checks both cases.

Formatter12973 passes36.991s. Final focused50366 is reaped PASS142.545s:26 cases,
zero failures/errors/skips, unchanged4,551 app/library inputs and quality checks.
Final full81742 is reaped PASS511.375s: 4,224 fresh cases (3,587 desktop,
613 Android host and24 browser), zero failures/errors/skips. Browser XML verifies
the exact21 domain and3 IndexedDB cases, including the coincident-denial regression.
All4,551 application/library inputs match the final focused manifest. Root checks,
release compilation/lint, Wasm/iOS source and quality pass. Earlier failed attempts
remain in the [accepted evidence](measurements/collaboration-space-room-binding-preparation-2026-11-09.json).
Hosted run42628449460 succeeds on source78076d4e. Verified artifacts contain9,003 cases:
3,035 desktop,613 Android host,2,898 browser and2,457 Apple simulator, with zero failures,
errors or skips. The21 preparation cases and3 actual storage cases are present on all
three shared targets, including Apple SQLite. Xcode builds the unsigned simulator host.
[Hosted evidence](measurements/collaboration-space-binding-hosted-2026-11-09.json) records
both artifact digests and precise scope. No physical iPhone, native SDK6 Matrix delivery,
APNs/calling, signed distribution, APK or product activation is implied.
