# Employee profiles

The profile screen uses current-account employment discovery from HRIS Platform.
It retains one bounded page and loads assignment references and private profile
fields for the selected employment. An unlinked account receives an empty state;
no directory fallback or sample employee is substituted.

## Ownership

`features/people/domain` owns entities, the repository contract, and the two read
use cases. `data` owns API services, the raw datasource contract, bounded DTO
mapping, and the repository implementation. `presentation` owns the profile MVI
state, separately registered events, widgets, and its navigation entry. Home
requests navigation through an effect without depending on the people feature.

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

## Validation

The full mobile check and Android dev build passed; Wasm compilation passed.
Nine people domain/data tests cover scope changes, cancellation, person linkage,
DTO validation, paging, and technical failure mapping. Seven application UI tests
cover native login/MFA, company changes, profile fields and assignments, revoked
access, in-place MFA renewal, language changes, and logout. API responses in these
UI tests use a test-only HTTP engine. A separate live API probe verified native
login, verification of an already enrolled authenticator, and empty self discovery
for the unlinked development account. Physical HRIS device validation is pending.
