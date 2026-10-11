# Work calendar

Employees open a seven-day work calendar from Home. It shows shift times, breaks,
work mode, the applicable timezone, roster overrides, holidays and unassigned days.
Overnight shifts retain their starting work date. Previous/next-week navigation is
bounded to one year either side of the server's company date.

The account's employment directory is paged at 20 entries. Only employments owned
by that account are selectable. A company without a linked employment shows an
empty state. Workforce requires its own enabled module and `attendance.self.record`
or `workforce.read`; disabling People does not disable the calendar.

## Acquisition and ownership

`RefreshWorkCalendar` coordinates session, workspace, company policy, remote
calendar and cache repository contracts. It checks current ownership after pending
reads and before publication. One application-owned mutex serializes acquisition;
an acquisition has a 30-second timeout and no implicit retry.

Acquisition reads current company client policy, the self-service employment
directory and one seven-day calendar. Directory/calendar responses have separate
byte limits. Their mappers validate scope, unique IDs, dates, contiguous days,
shift revisions and elapsed time before producing domain values.

The route owns its MVI ViewModel and observation lifetime. Navigation cancels an
obsolete acquisition; stopping the route releases collectors and clears displayed
private content. A late snapshot cannot replace a newer selection. Repositories
perform no navigation, permission requests, scheduling or UI-state management.

## Offline access

Each company retains one complete encrypted calendar snapshot. A new successful
week replaces the previous week atomically; this is not a full schedule replica.
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

Root checks passed 350 JVM/UI tests, including acquisition cancellation, late
results, scope/MFA changes, encrypted cache isolation and disposal. The new browser
timezone test, Android dev build and production Web build passed. API 24 and API 35
emulator checks used the real dev API for overnight shifts, week navigation,
company isolation, offline process restoration and recovery. English/Indonesian
and light/dark captures were inspected. iOS physical acceptance remains separate.

The validated dev APK was also installed on the authorized Android phone using
`--install-only` on 2026-10-11. Installation and package metadata were checked;
no phone UI acceptance was performed during that delivery.

Attendance capture, offline attendance verification and overtime requests are
separate workflows and are not implemented by this read-only calendar.
