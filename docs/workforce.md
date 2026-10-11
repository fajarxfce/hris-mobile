# Attendance and work calendar

Attendance and work calendar are available from Home shortcuts and the Work
section in Services. Their routes, MVI owners, repository adapters and policies
belong to `features/workforce`; generic foreground location belongs to
`core/location`. Neither workflow depends on the People module being enabled.

## Attendance capture

Employees can record clock-in and clock-out for their own active or probationary
employment. The work date follows the company timezone; a running overnight shift
retains its starting date. The current UI supports one pair per work date, with
today and five previous days shown in history. A corrected day cannot be captured
again. The server remains authoritative for ownership, sequencing and acceptance.

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

## Work calendar

Employees open a seven-day work calendar from Home. It shows shift times, breaks,
work mode, the applicable timezone, roster overrides, holidays and unassigned days.
Overnight shifts retain their starting work date. Previous/next-week navigation is
bounded to one year either side of the server's company date.

The account's employment directory is paged at 20 entries. Only employments owned
by that account are selectable. A company without a linked employment shows an
empty state. Workforce requires its own enabled module and `attendance.self.record`
or `workforce.read`; disabling People does not disable the calendar.

## Snapshot acquisition and ownership

`RefreshWorkCalendar` coordinates session, workspace, company policy, remote
calendar and cache repository contracts. It checks current ownership after pending
reads and before publication. One application-owned mutex serializes acquisition;
an acquisition has a 30-second timeout and no implicit retry.

Acquisition reads current company client policy, the self-service employment
directory and one seven-day calendar. Directory/calendar responses have separate
byte limits. Their mappers validate scope, unique IDs, dates, contiguous days,
shift revisions and elapsed time before producing domain values.

`RefreshAttendance` separately acquires a seven-day window around the company
date, the employee's frozen/effective calendar and attendance entries. Its cache
also retains the selected directory cursor, so a late snapshot from another page
cannot replace the requested employment page. Both snapshot workflows recheck
authority after pending I/O and before publication.

The route owns its MVI ViewModel and observation lifetime. Navigation cancels an
obsolete acquisition; stopping the route releases collectors and clears displayed
private content. A late snapshot cannot replace a newer selection. Repositories
perform no navigation, permission requests, scheduling or UI-state management.

## Offline access

Each company retains separate encrypted calendar and attendance snapshots. A new
successful acquisition replaces that workflow's snapshot atomically; this is not
a full schedule or attendance replica.
Its content key is separate from inbox encryption. Authentication data binds the
encrypted document to the audience, account, session, company and generation.
Publication uses the database's account ownership and compare-and-set revision.

Cached reads last at most one hour and never outlive the workspace admission,
session/MFA authority or a known upcoming maintenance window. Renewing admission
does not extend an old snapshot. Observation owns one cancellable deadline rather
than polling. Transport failure retains still-admitted data; authoritative access
loss hides it and attempts an atomic cache removal. A cleanup failure cannot replace
the original access failure. Missing content keys do not silently regenerate keys
for existing ciphertext.

Android/iOS support encrypted process restoration through their secure key stores.
The desktop host uses its configured credential storage. Browser keys remain in
memory, so Web does not claim offline process restoration after closing the page.

## Platform dates and validation

Calendar dates use `formatLocalDate`, independently of the device timezone. Android
core library desugaring supports the declared API 24 minimum. Web explicitly imports
IANA timezone data compatible with the `@js-joda/core` version used by
`kotlinx-datetime`; its browser regression covers overnight dates and daylight-saving
transitions. Keep that compatibility when updating the npm lock.

Root checks passed 366 JVM/UI tests. Attendance regressions cover cancellation
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

Overtime requests, a long-term attendance replica, mobile review/correction,
biometric or hardware-backed location attestation and background GPS tracking
are outside this employee attendance increment. Desktop and Web explicitly report
location as unavailable; users can choose capture without location for review.
