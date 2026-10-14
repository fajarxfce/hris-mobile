# Attendance and work calendar

[Overtime](overtime.md) shares the Workforce module and frozen schedule contracts.

Home includes today's clock-in/out card above its service grid. Its capture
ViewModel owns acquisition, permission recovery and pending-command actions.
Leaving its tab or backgrounding cancels acquisition and user actions. Read-only
cache/admission observation lives with the navigation ViewModel, so hidden content
still observes ownership and access changes. Re-entering a loaded, admitted Home does not clear
the card or repeat its request. Incomplete acquisition, invalidated content and
explicit actions require refresh. Clearing the owner releases its subscriptions.
Stored, received, pending review and accepted outcomes remain distinct; corrections
replace the displayed times without enabling another capture.
Saved content remains visible when stale and during ordinary refresh. Freshness
has no read-expiration timer. A failed refresh reports its cause and waits for an
explicit retry; hidden Home data does not trigger HTTP requests. A confirmed
access denial closes observation until admitted publication succeeds.

Attendance history and work calendar are available from Home shortcuts and the Work
section in Services. Their routes, MVI owners, repository adapters and policies
belong to `features/workforce`; generic foreground location belongs to
`core/location`. Neither workflow depends on the People module being enabled.

## Attendance capture

Employees can record clock-in and clock-out for their own active or probationary
employment. The work date follows the company timezone; a running overnight shift
retains its starting date. The capture UI supports one pair per work date.
A corrected day cannot be captured again. The server remains authoritative for ownership, sequencing and acceptance.

`CaptureAttendance` coordinates current session/workspace, admitted attendance
snapshot, location and command repository contracts. It owns permission policy,
location freshness, a bounded capture-window request and immutable command
creation. It never invokes another use case. A denied permission offers an
explicit retry, Settings action or capture without location. Returning from
Settings does not silently record attendance. Location acquisition is foreground,
one-shot and limited to 15 seconds; stopping the route cancels pending work.

Online capture requests a server window before retaining the command. A network
failure or timeout permits offline capture; MFA, access and validation failures
do not silently switch to offline mode. Capture without location is explicitly
marked offline. Device coordinates and mock-location indicators are evidence,
not hardware attestation. The opaque native session ID is the device label for
this integration and is not a stable hardware identity.

The command is encrypted and stored before attendance submission. Its identifier,
work date, timestamp, capture window and coordinates remain unchanged on retry,
including after process restoration or a new session for the same account.
Request and outcome encryption bind audience, account, company, resource and
revision. Storage enforces a unique employee/date/action resource and atomic
revision checks. Missing keys never silently replace keys for retained commands.

`ReplayAttendance` serializes manual and worker delivery through one owner. It
rechecks company availability before transport, persists an uncertain outcome
before submitting and retains it when a response is lost. Retries reuse the
original idempotency key and payload. Automatic delivery is bounded to five
attempts per command, ten deliveries and 30 seconds per run; manual retries also
have a finite limit. Cancellation cannot erase an uncertain submission. Only a
definite rejection can be explicitly dismissed, subject to its revision check.

Receipt, pending review and accepted time are distinct UI states. Mocked, missing
or offline evidence can require independent review in the administrative
dashboard. A receipt does not claim accepted working time. Successful refreshes
can retire received commands once their entries are present in the scoped
snapshot. Server review and correction stay in HRIS Platform.

## Attendance history

Attendance is a read-only monthly view: present days, confirmed absences, days
needing review, verified time and daily evidence. It contains no capture controls.
The fixed month filter offers adjacent months and a localized month/year picker;
future months are unavailable. Current-month totals exclude future dates. A missing
record is not automatically an absence, and an ongoing overnight shift is not
incomplete merely because the company date has changed.

`AttendanceHistoryViewModel` coordinates only history observation and refresh.
Changing a month or employment switches the local query without starting another
HTTP request or cancelling shared synchronization. Leaving the route cancels its
acquisition and observation. Day details are read-only. Every acquired attendance
day remains available independently of the last selected month. A new route opens
the current company month; the company timezone and local clock determine that date.
An empty day is unrecorded, not proof of absence. Before the first complete replica,
only the exact previously acquired legacy snapshot is available offline.

Capture stays on Home. Pending attendance opens a controlled dialog with explicit
retry and rejected-command dismissal. Employment selection has its own dialog.
These surfaces share the Home capture owner; closing a dialog does not cancel a
submission, while leaving its tab or stopping the app does. Returning from Settings
still requires a new explicit capture action.

## Work calendar

Employees open a seven-day work calendar from Home. It shows shift times, breaks,
work mode, the applicable timezone, roster overrides, holidays and unassigned days.
Overnight shifts retain their starting work date. Previous/next-week navigation
queries local records without the previous one-year navigation restriction. Each
query covers seven days; this does not discard other weeks.

The account's employment directory is paged at 20 entries. Only employments owned
by that account are selectable. A company without a linked employment shows an
empty state. Workforce requires its own enabled module and `attendance.self.record`
or `workforce.read`; disabling People does not disable the calendar.

## Incremental acquisition and ownership

`SyncWorkforce` coordinates current session, selected company, client policy,
self-service employment directory, change feed, canonical records and replica
repository contracts. One application-owned execution mutex covers UI and worker
triggers. A run is limited to 30 seconds including its lock wait, ten feed pages,
32 references per page and four concurrent canonical requests. A later invocation
resumes the checkpoint. These work limits do not expire or evict acquired history.

The feed explicitly selects four account-owned projections:

| Collection | Stored fact |
| --- | --- |
| `ATTENDANCE_DAYS` | Stable employee/day ID, captured evidence, review and latest correction |
| `WORK_SCHEDULES` | Latest immutable weekly assignment per employee/effective date |
| `ROSTER_DAYS` | Stable employee/day override; a null shift explicitly means off |
| `WORK_HOLIDAYS` | Company date, name and active status for an account with owned employment |

The directory is acquired in pages of 20, with a 200-employment scope bound.
Canonical responses have independent byte limits. Mapping validates IDs, scope,
versions, dates and shift values. An invalid response, failed hydration or cancelled
run cannot advance its cursor. Newer canonical versions are accepted; older updates
cannot overwrite them. Collection and ID identify a record, including tombstones.

Documents, directory and checkpoint commit in one storage transaction. Initial and
replacement bootstraps stage their records until complete. Cursor retention expiry
stages a successor while the prior published generation stays readable. A new login
for the same account starts its own cursor and retains owned facts. A different
account/company never receives those facts as a fallback. Confirmed projection
scope loss requires removing the old projection before rebuilding it.

## Local queries

`ObserveAttendance` and `ObserveWorkCalendar` read storage only. They match directory
and queried facts by generation and revision, so a racing commit cannot combine
old employment metadata with newer data. Employment choice, monthly history and
weekly navigation do not invoke acquisition.

Four indexed queries read attendance, schedule, roster and holiday records for one
owned employment. A monthly query reads at most 132 documents, including the most
recent schedule baseline preceding its date range. HMAC tags bind the collection,
account, company and employment. Authenticated encryption covers scope, identity,
version, ordering and tags. The indexes reveal structural ordering/equality, not
unencrypted attendance contents. Missing keys never silently replace keys protecting
retained records.

Pure domain policy resolves roster overrides first, then active holidays, then the
latest effective weekly assignment. An explicit roster day off remains an override;
an inactive holiday does not block work. Unassigned dates remain unassigned.
Overnight shifts, timezone offsets, daylight-saving gaps and overlaps are handled
without SDK types in domain. A locally resolved calendar does not invent the server's
global schedule revision; server-reviewed command input retains its own contract.

`summarizeAttendanceHistory` keeps confirmed absence, missing records, days off,
unassigned schedules and review status distinct. Future dates do not enter current
month metrics. Pending commands never add accepted hours. A confirmed command is
retired only when its exact server entry and receipt version appear in local facts,
including an older day outside the current Home window.

## Offline access and admission

Audience, account and company own the encrypted replica independently of the login
session. Every storage operation still checks the active session, rejecting late
writers after account replacement. Before the first complete replica is published,
observers can read a matching pre-upgrade calendar/recent/monthly snapshot. A complete
replica's empty result never falls back to an unrelated legacy view.

`refreshAfter` marks freshness only. It does not hide local records, clear them on a
timer or force a loading screen. Local startup and navigation do not wait for HTTP.
Ordinary age, expired credentials and cursor retention are distinct from confirmed
access loss. Capture preparation, server windows and reviewed transactions retain
separate operation-specific checks; refreshing metadata cannot renew old evidence.

Home, history and calendar share an encrypted read-admission record. An authoritative
MFA, company or projection denial closes every affected local projection. Repositories
persist the supplied restriction; use cases decide when to restrict or reopen reads.
A process-owned write buffer preserves the denial while storage is pending or fails.
Only a complete, admitted acquisition that started after the latest denial can clear
it. Pending/failed retries and late older completions cannot reopen the data. Durable
restrictions survive process restoration and reauthentication; failed durable writes
can only retain their restriction for the current process.

Android/iOS restore encrypted records through their secure key stores. The desktop
host uses its configured credential storage. Browser keys remain in memory, so Web
does not claim offline process restoration after closing the page.

## Platform dates and validation

See the [latest validation](validation.md#monthly-attendance-interface) for the
monthly screen, offline recovery, and current build/device observations.

Calendar dates use `formatLocalDate` and month labels use `formatMonth`,
independently of the device timezone. Android
core library desugaring supports the declared API 24 minimum. Web explicitly imports
IANA timezone data compatible with the `@js-joda/core` version used by
`kotlinx-datetime`; its browser regression covers overnight dates and daylight-saving
transitions. Keep that compatibility when updating the npm lock.

The earlier capture integration passed 366 JVM/UI tests. Attendance regressions cover cancellation
during GPS acquisition, permission recovery, company changes, encrypted process
restoration, duplicate capture, uncertain delivery, revision checks and observation
disposal. The workforce and location browser suites passed 25 tests after the
isolated workforce test runtime registered the same IANA rules as the Web host.
The Android dev APK and production Web distribution built successfully.

API 35 emulator acceptance used the real dev API and a simulated location provider:
denied and permanently denied permission, recovery after Settings, offline capture,
force-stop/reopen, explicit retry and online clock-out. The server retained exactly
one clock-in and one clock-out, both pending review, with zero accepted minutes.
No active application location request remained after leaving the screen.
English/Indonesian, light/dark and 130% text captures were inspected. Prior calendar
acceptance also covered API 24, overnight shifts, week navigation and company
isolation. Physical GPS accuracy and iOS acceptance remain separate.

The validated dev APK was installed on the authorized Android phone using
`--install-only` on 2026-10-11. Installation and package metadata were verified;
phone UI acceptance was not performed during that delivery.

Mobile review/correction, biometric or hardware-backed location attestation and
background GPS tracking remain outside this employee attendance increment. Overtime
has its own [replica and workflows](overtime.md). Desktop and Web explicitly report
location as unavailable; users can choose capture without location for review.
