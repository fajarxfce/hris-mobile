# Leave

The employee Leave page shows admitted leave balances by year and paged request
history with status filtering. Home and All services expose it when the selected
company enables Leave and the account has self-service or read permission.
Request creation and other mutations are subsequent integrations.
Selecting a request opens its submitted facts, current status, dated portions,
reason, evidence names and paged activity history.

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

`RefreshLeaveRequest` and `ObserveLeaveRequest` provide the separate request-detail
read boundary. The API uses `/leave/me/requests/{id}`: an HR or approval grant does
not broaden this route beyond the account's own requests. It returns submitted
policy/day facts, current workflow metadata, evidence references and at most ten
history revisions. Older history pages retain the current request header.
Transport mapping validates IDs, exact half-day totals, ordering, cursors and
bounded fields. The feature owns the detail route and its MVI ViewModel; app
composition connects the overview's typed navigation effect to that route.

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

Request details have an independent last-view partition per company, sharing the
account credential without replacing the overview. Up to 366 dates and ten
history entries use a header, date chunks and individual history records, each
limited to 96,000 bytes. Counts and record purposes are authenticated; missing,
duplicate or foreign records cannot restore a partial view. Both partitions use
complete compare-and-set replacement. A missing encryption key cannot be
regenerated over either existing partition.

A read lease lasts at most one hour and cannot outlive workspace admission, the
native session or known maintenance. Authority loss removes content; a transport
failure may retain an otherwise valid view. Cancellation or a replaced workspace
cannot publish old results or clear the replacement scope. There is no automatic
retry, background polling or permission orchestration in the data layer.

## Presentation

The feature owns its route, MVI ViewModel, declarative page and filter dialogs.
The route collects state with lifecycle awareness and pairs start with
stop/disposal. Leaving cancels acquisition and observation; replacing filters
cancels obsolete reads. The ViewModel holds an authoritative read failure through
retry and resubscribes only after fresh publication. A failed local cleanup cannot
silently make restricted content visible again.
The gate also survives subsequent network or storage failures; those errors do
not authorize reading the previous view.
An upstream storage failure can complete the cache flow without ending the outer
state collector. A successful explicit refresh reopens observation in both the
overview and detail screens; errors never start an automatic retry loop.

Balances have their own year filter; request history explicitly covers all dates.
Year and status controls remain usable after a transport failure. Cursor actions
accept only the visible next page or first page, and employment choices remain
limited to the account-owned directory. Offline restoration retains the saved
filter whether the cache arrives before or after the transport failure. The
screen shows the last update time and never infers a balance from request rows.

Detail history accepts only the visible next revision cursor or the latest page.
Returning to the latest page cancels an older pending read, and an unavailable
earlier page does not trap navigation. Viewing earlier revisions keeps the
server's current header rather than presenting an old status as current. Offline
restoration retains the last history selection. A missing or revoked request
hides its saved content, including through subsequent failed retries.

## Remaining workflow

Subsequent work adds eligible policy/day selection, document evidence acquisition
and transfer, durable submission, withdrawal
and cancellation, plus canonical sync reconciliation. A cached balance is never
a reservation or authorization to submit.

## Validation

The root `:check` passes with 491 desktop tests. Leave's three Chromium suites
have passed 51 tests; the 20 presentation tests were rerun for request details,
while domain/data passed 31 tests for the preceding read boundary. Android dev
and the Web production build pass. Coverage includes
exact half-day values, bounded pages, session/company isolation, encrypted cache
restoration, cancellation, late results and compare-and-set publication. MVI tests
exercise denied reads, pending retries, expiry, disposal and both cache/network
arrival orders. Assembled UI tests cover independent module admission, filter
restoration, company replacement and Indonesian at 200% text.

The API 35 emulator read a fictional 12.5-day allowance from the development
server. Year/status filtering, dark/light appearance, offline process restoration
and online recovery were checked. The saved 2025/approved filter survives restart
and refresh. A fictional half-day request was subsequently created through the
development API and read on the mobile detail page as pending. Its submitted
date, first-half portion, reason and activity match the owner-only API. Restarting
the app offline restores the request, and refresh recovers online. Light/dark
appearance and 200% text were inspected on the emulator. Earlier history pages
use an automated transport fixture; the real request has one history entry.
These observations do not validate mobile submission. Physical iOS
acceptance and transactional workflows remain separate work.
