# People

The profile screen uses current-account employment discovery from HRIS Platform.
It retains one bounded page and loads assignment references and private profile
fields for the selected employment. An unlinked account receives an empty state;
no directory fallback or sample employee is substituted.

## Ownership

`features/people/domain` owns entities, the repository contract, and the read
use cases. `data` owns API services, the raw datasource contract, bounded DTO
mapping, and the repository implementation. `presentation` owns separate profile
and directory MVI state, typed event handlers, widgets, and navigation entries.
App composition connects the directory tab and standalone route. Home requests
navigation through an effect without depending on the people feature.

Use cases check the originating workspace, account session, API audience, and
person linkage before returning data. Repositories acquire and map within
`safeApiCall`; cancellation propagates. Mapping rejects mismatched resource IDs,
duplicate entries, invalid dates, invalid reference relationships, and cursors
that cannot advance. Individual HTTP requests remain bounded; directory synchronization acquires records in finite batches.

The company date comes from the server. Calendar dates remain date-only values,
so rendering a birth date or start date does not apply an implicit timezone shift.
A transferred person's private profile may retain its original owning company;
current account/person linkage still controls self access.

## Screen lifetime

Entering or resuming the profile requests current data. Refresh and selection
clear the previous private fields. A new selection cancels the former detail
request; a company/account change disposes the route owner. Late results cannot
be published into another workspace. Reads have no automatic retry loop.

An MFA rejection clears the profile and offers verification. Completing the
challenge returns to the same company and triggers a fresh authorized read.
General access failures also clear private fields. This screen currently uses
online reads; it does not grant offline access or persist employee PII.

## Coworker directory

Employees reads an account-owned encrypted replica. `SyncCompanyDirectory` selects
only `DIRECTORY_EMPLOYEES`, consumes bounded bootstrap/change pages, and hydrates
`employees/directory/{id}` through `DirectoryRecordRepository`. The server grants
this collection through `people.directory.read` or `people.read`; private profile
or employment linkage is not required and no private profile is replicated.

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
employee records. Private profile reads remain online.

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
