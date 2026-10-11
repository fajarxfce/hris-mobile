# Leave

The domain/data foundation reads the signed-in account's employment references,
leave balances by year and paged request history. Presentation and durable leave
commands are the next integration; this foundation does not yet add a menu or
submit a leave request.

## Ownership

The Leave feature uses its own `/leave/me/employments` endpoint. Disabling People
or Workforce does not disable leave discovery. Every balance/request read carries
its originating session and company; the HTTP authentication adapter owns token
attachment and coordinates refresh through the existing identity use case.

Datasources implement raw API contracts. Repositories map responses and technical
failures inside `safeApiCall`; storage acquisition, decryption and DTO mapping stay
inside `safeStorageCall`/`safeStorageFlow`. `RefreshLeave` coordinates repositories,
checks workspace and live module availability, validates the selected employment,
and publishes only a complete view. It rechecks scope after each pending read.
`ObserveLeave` owns the admitted cache subscription and its finite expiry timer.

Balances use integer half-day units. The client never recomputes available days
from the paged request list. Balance and request endpoints are independent server
reads; they are not represented as an atomic accounting snapshot. Pages contain
at most 20 items with server cursors, and DTO mapping checks ownership, ordering,
bounds and supported values. Year selection follows the server's 1900–2200 range.

## Offline access

One encrypted overview is retained per company: selected employment, year, status
and cursors, balance page, and request page. It is a bounded last-view fallback,
not a full leave replica. Complete replacements use the observed generation,
account-owned storage and authenticated encryption bound to session, account,
audience, company, generation and record purpose. No page publishes in parts.
The encoded view is limited to 96,000 bytes. Key and plaintext byte buffers are
cleared after use; cancellation is checked before publication.

A read lease lasts at most one hour and cannot outlive workspace admission, the
native session or known maintenance. Authority loss removes content; a transport
failure may retain an otherwise valid view. Cancellation or a replaced workspace
cannot publish old results or clear the replacement scope. There is no automatic
retry, background polling or permission orchestration in the data layer.

## Remaining workflow

Connect the feature-owned MVI page, localized balances/history, filter and pagination
controls, and emulator acceptance. Subsequent work adds scoped request details,
eligible policy/day selection, document evidence, durable submission, withdrawal
and cancellation, plus canonical sync reconciliation. A cached balance is never
a reservation or authorization to submit.

## Validation

The foundation passes the root `:check` task (447 desktop tests across the
workspace) and both Leave Chromium test suites. Its 22 desktop tests cover exact
half-day values, bounded pages, session/company isolation, encrypted cache
restoration, cancellation, late results and compare-and-set publication. This
slice does not yet include an Android UI or device acceptance for Leave.
