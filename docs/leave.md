# Leave

The employee Leave page shows admitted leave balances by year and paged request
history with status filtering. Home and All services expose it when the selected
company enables Leave and the account has self-service or read permission.
Request creation and other mutations are subsequent integrations.

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
bounded fields. The request-detail screen is the next integration step.

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

Balances have their own year filter; request history explicitly covers all dates.
Year and status controls remain usable after a transport failure. Cursor actions
accept only the visible next page or first page, and employment choices remain
limited to the account-owned directory. Offline restoration retains the saved
filter whether the cache arrives before or after the transport failure. The
screen shows the last update time and never infers a balance from request rows.

## Remaining workflow

Subsequent work adds the request-detail screen,
eligible policy/day selection, document evidence, durable submission, withdrawal
and cancellation, plus canonical sync reconciliation. A cached balance is never
a reservation or authorization to submit.

## Validation

The root `:check` passes with 478 desktop tests. Leave's three Chromium suites
have passed 40 tests; domain/data were rerun for request-detail reads, while the
unchanged presentation suite previously passed nine tests. Android dev passes;
the preceding overview and retry changes also passed the Web production build. Coverage includes
exact half-day values, bounded pages, session/company isolation, encrypted cache
restoration, cancellation, late results and compare-and-set publication. MVI tests
exercise denied reads, pending retries, expiry, disposal and both cache/network
arrival orders. Assembled UI tests cover independent module admission, filter
restoration, company replacement and Indonesian at 200% text.

The API 35 emulator read a fictional 12.5-day allowance from the development
server. Year/status filtering, dark/light appearance, offline process restoration
and online recovery were checked. The saved 2025/approved filter survives restart
and refresh. A fictional half-day request was subsequently created through the
development API and verified as pending using the owner-only detail endpoint;
this does not validate mobile submission or a detail screen. Physical iOS
acceptance and transactional workflows remain separate work.
