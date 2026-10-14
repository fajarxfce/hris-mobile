# People

The profile screen reads account-owned encrypted employment and personal-profile
records. Incremental synchronization acquires coherent snapshots from HRIS Platform;
selection reads local data. An unlinked account receives an empty state.

## Ownership

`features/people/domain` owns entities, repository contracts, use cases and pure
access/selection policies. `data` owns API services, raw datasource contracts,
bounded DTO mapping, encryption and repository implementations. `presentation`
owns separate profile and directory MVI state, typed handlers, widgets and
navigation entries. App composition connects the directory tab and profile route.

Use cases coordinate local ownership, live company admission and bounded feed
acquisition. Repositories acquire and map within `safeApiCall` or
`safeStorageCall`; cancellation propagates. Mapping rejects mismatched IDs,
account/person linkage, dates, independent revisions and unresolved assignments.
The company date comes from the server. Birth dates, start dates and effective
dates remain date-only values without an implicit timezone shift. A transferred
person may retain their original owner company while the employment belongs to
the selected company.

## Private profiles

`SyncSelfProfiles` explicitly selects `SELF_PROFILES` and hydrates
`employees/me/profiles/{employeeId}`. Each canonical response contains employment,
private person fields and resolved organization/manager references from one server
snapshot. Its sync revision is separate from employment/person command versions.
The response's `asOf` date records when facts were projected; ordinary date changes
do not expire the profile or create artificial content revisions.

Access requires `people.self.read`, or both `people.read` and
`people.profile.read`. Even an administrator reads only employments linked to
the current account. The People module must be available. Public-directory access
does not admit a private profile.

Each run has a 30-second execution budget, at most ten feed pages, fifty references
per page and four concurrent canonical reads. A shared use-case mutex coordinates
UI and worker requests. Initial and replacement generations publish only after
feed catch-up reports no pending publication; prior complete records stay readable.
Records and the processed cursor commit atomically. A versioned withdrawal or
`self_profile_not_found` removes that employment. Older versions cannot restore it.

The private replica and read-admission record have separate partitions from the
coworker directory and use their own platform-protected content key. Account,
company and API audience bind the ciphertext; a session must match the active
storage owner. No name, birth date, email or employee number appears in plaintext
record indexes. Local observation admits at most 200 owned employment records and
checks one committed revision throughout acquisition. A missing retained key is
reported instead of silently replacing it.

`ObserveSelfProfiles` combines local records with the selected employment ID.
Selection retains a still-present ID or chooses the first employee number. It does
not request HTTP or renew authority. Owned records survive process restart and
reauthentication. Neither ordinary data age nor credential/cursor expiry hides
them. Cursor expiry starts a staged replacement while local facts remain visible.

## Profile screen lifetime

Entry restores local content immediately and requests finite synchronization when
needed. An explicit refresh always requests acquisition while retaining existing
facts. The refresh interval controls scheduling only. Employment selection opens
a compact radio-list dialog and performs a local query. An unknown selection or
late emission for the preceding selection cannot replace current state.

Leaving the foreground cancels acquisition while the route-owned local collector
continues watching ownership and read admission. Disposal cancels that collector.
A confirmed access/MFA denial hides private content even if cleanup fails. The
encrypted denial survives restart; a process-owned buffer protects failed writes
through reauthentication. Only admitted acquisition that observed the latest denial
revision may reopen it. Transport failures and incomplete retries cannot clear it.
MFA recovery returns to the selected company before admitted synchronization.
Successful explicit acquisition also reopens a completed storage subscription;
there is no automatic retry loop in the read boundary or UI.

## Coworker directory

Employees reads an account-owned encrypted replica. `SyncCompanyDirectory` selects
only `DIRECTORY_EMPLOYEES`, consumes bounded bootstrap/change pages, and hydrates
`employees/directory/{id}` through `DirectoryRecordRepository`. The server grants
this collection through `people.directory.read` or `people.read`; private profile
or employment linkage is not required. This collection excludes private profile fields.

Each record contains the employee number, name, position/department identifiers
and their labels. Versioned deletions remove withdrawn coworkers. A canonical
`directory_employee_not_found` also removes that individual; it does not revoke
the whole company directory. Old record versions cannot restore a tombstone.

The repository commits encrypted records and their processed cursor atomically.
An initial or replacement bootstrap stays unpublished until change-feed catch-up
reports no pending publication. The preceding complete generation remains readable
while a replacement is incomplete or the network is unavailable. Ordinary data,
credential and cursor age do not withdraw owned local records. Cursor expiry starts
a replacement; live authorization and confirmed denials have separate gates.

`ObserveCompanyDirectory` verifies current local ownership and directory admission,
then combines the stored snapshot with the screen's query/page/department selection.
It never acquires data. Search includes all acquired employees, including pages the
user has not opened. Pages contain at most 20 employees; the department picker shows
up to 50 matches and can narrow its query. Neither operation stores plaintext names
or search terms in database indexes. Storage reads at most 200 encrypted records per
batch, checks that every batch belongs to one committed revision, and cancels an
obsolete scan when that revision changes. A navigation-owned collector retains the
decoded snapshot for filtering and releases it when its owner is disposed.

A confirmed access/MFA denial closes observation even when record cleanup fails.
An encrypted admission record preserves the denial across process restoration; a
process-owned buffer also retains failed admission writes across reauthentication.
Only acquisition that observed the current denial revision can reopen it. A later
denial wins over an older pending acquisition. Account, company and API audience
remain separate storage boundaries, and stale sessions cannot commit data.

## Directory screen lifetime

Browsing and search have separate navigation-owned MVI instances. Search opens a
page with a fixed Back action and focused input. Query replacement is debounced;
department/page changes query local records. Returning from search retains the
browsing selection and scroll. Reopening search starts empty.

Successful tab changes do not repeat HTTP. Routine entry can reuse a recently
synchronized replica; explicit refresh always requests acquisition. This scheduling
interval never limits local reads. Leaving a route stops acquisition; interrupted
refresh resumes on return. The retained tab continues observing local ownership and
read admission, so hidden rows are cleared after access is lost. Route disposal
cancels the storage collector. Background synchronization uses the same bounded
use case and execution mutex as manual refresh.

## Validation

Regression coverage includes full-directory search, department/page selection,
credential/data age, account/company changes, cursor replacement, publication
pending, versioned deletion, raw DTO bounds, cancellation, late results, failed
admission persistence and disposal. SQLite and IndexedDB run the same replica
contract. The assembled UI tests cover independent search ownership, offline search
and filters, retained tab scroll, interrupted refresh and access recovery.

Development fixture data contains one current coworker. Larger directories and
fault scenarios use isolated automated fixtures; they do not create production
employee records.

The directory integration passed the root checks with 1,397 desktop/JVM tests,
59 Chromium tests, Android dev assembly and the production Web build. API 35
emulator acceptance verified the live directory, cold offline restoration,
offline search/filtering and another offline restart after a 73-hour clock advance.
The emulator clock and network were restored. The validated APK was delivered to
the authorized phone with install-only delivery.

A JVM fixture with 5,000 records measured 90 ms for reading/decrypting the complete
directory and 7 ms for a query reaching its final record. These measurements do not
describe phone performance; the fixture also asserts that unvisited pages remain
searchable.

The private-profile integration passed the root checks with 1,475 desktop/JVM
tests, 86 Chromium People tests, Android dev assembly and the production Web build.
Coverage includes independent private grants, canonical identity/reference checks,
versioned withdrawals, pending publication, scoped encrypted restoration, failed
admission writes, cancellation, local selection and completed storage-flow recovery.
API 35 acceptance verified the live profile, cold offline restoration, another
restart after a 73-hour clock advance and retained facts after a failed refresh.
The emulator clock and network were restored, and its crash buffer contained no
fatal exception or signal. The validated APK was installed on the authorized
Android 16 phone on 14 October 2026 at 21:13:23 WIB with install-only delivery.
Package metadata confirmed the update; its UI was not opened or driven.
