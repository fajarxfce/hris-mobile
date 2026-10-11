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
that cannot advance. No request downloads an entire company directory.

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

Employees uses `employees/directory` under `people.directory.read` or `people.read`,
independently of private profile access. It shows only employee number, name,
position and department for current coworkers. These rows do not navigate to
someone else's private profile. The server selects eligible employments using
the company date and rechecks access under its guards.

Search is debounced and each page contains at most 20 employees. A replacement
query cancels the former read, while a refresh of the same page retains content
after a transient network failure. Access failures clear the page. Leaving the
tab or covering the route stops its request; returning revalidates current
access. No directory is persisted for offline access, and paging requires an
explicit action rather than an unbounded prefetch loop.

## Validation

Domain/data and assembled UI tests cover scope changes, cancellation, late results,
person linkage, DTO validation, paging, search replacement, failure mapping,
revoked access, MFA renewal and tab disposal. UI API responses use a test-only HTTP
engine. A separate live API probe verified company-scoped directory results and
search with the employee role. Android emulator checks cover the directory tab,
standalone route, search, Back behavior, English/Indonesian and light/dark layouts.
Development fixture data contains one current employee in its first company;
larger pages and access changes are exercised by the automated fixtures.
