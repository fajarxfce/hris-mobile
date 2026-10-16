# Leave

The employee Leave page shows admitted leave balances by year and paged request
history with status filtering. Home and All services expose it when the selected
company enables Leave and the account has self-service or read permission.
Employees with self-service write access can prepare and submit a new request.
Selecting a request opens its submitted facts, current status, dated portions,
reason, evidence names and paged activity history.

## Ownership

The Leave feature uses its own `/leave/me/employments` endpoint. Disabling People
or Workforce does not disable leave discovery. Every balance/request read carries
its originating session and company; the HTTP authentication adapter owns token
attachment and coordinates refresh through the existing identity use case.

Datasources implement raw API contracts. Repositories map responses and technical
failures inside `safeApiCall`; storage acquisition, decryption and DTO mapping stay
inside `safeStorageCall`/`safeStorageFlow`. `SyncLeave` coordinates the feed,
canonical-resource, directory and replica repository contracts. It checks workspace
and live module availability and rechecks ownership after pending work. The same
application-owned execution guard serves UI, reconnect and worker triggers, and
is shared with individual request acquisition. Cached history reads bypass that
lock; finite acquisition deadlines include waiting for it.
`ObserveLeave` owns the scoped local subscription without an age-based read timer;
employment, year, status and page selection read the local replica.

`LoadLeaveRequest` and `ObserveLeaveRequest` provide the separate request-detail
read boundary. The API uses `/leave/me/requests/{id}`: an HR or approval grant does
not broaden this route beyond the account's own requests. It returns submitted
policy/day facts, current workflow metadata, evidence references and at most ten
history revisions. Older history pages retain the current request header.
Transport mapping validates IDs, exact half-day totals, ordering, cursors and
bounded fields. The feature owns the detail route and its MVI ViewModel; app
composition connects the overview's typed navigation effect to that route.

Balances use integer half-day units. The client never recomputes available days
from the paged request list. Balance and request endpoints are independent server
reads; they are not represented as an atomic server accounting snapshot. Local
balance/request pages share one published replica checkpoint and contain at most
20 items. DTO mapping checks ownership, ordering, bounds and supported values.
Year selection follows the server's 1900–2200 range.

When the server supplies a tracked carry award, the balance card separates the
recorded total from the carried amount included in it. The retained breakdown
contains the original award, available/reserved/used amounts, expiry, transfers
and deductions. An optional validity date uses the award's original company
calendar and stored UTC boundary; changing device timezone does not rewrite it.
Business validity never acts as a local-data expiry timer. Ledger totals and
versions change only through posted accounting operations. Request preview still
checks which funding is eligible for the requested dates.

The transport mapper validates half-day conservation, main-account subsets,
closed-account amounts, identifiers and paired date/instant metadata. Older or
untracked balances omit this metadata and retain their existing display. Optional
fields keep preceding encrypted JSON readable. Leave record format 3 starts one
replacement bootstrap so unchanged account versions can acquire their carry
metadata. The previous published generation remains visible through interrupted
acquisition, cancellation and process restart; publication replaces it atomically.
The [Android upgrade acceptance](validation.md#android-carried-balance-upgrade-acceptance)
verified this path with a public finite award: retained balances opened after an
offline APK upgrade, then acquired carry metadata without new ledger versions and
reopened after another offline cold start.

## Request preparation

`LoadLeaveTypes` reads a bounded effective-date catalog and removes inactive
choices without changing its server cursor. `PreviewLeaveRequest` calls the
owner-only `/leave/me/requests/preview` endpoint. The response retains the policy,
charged days, excluded dates and separate annual balance projections. It does
not reserve a balance or create a request. Preparation requires `leave.self.manage`;
an HR read/manage permission does not broaden this employee workflow.

Both use cases acquire fresh company policy, recheck session/workspace ownership
after pending work, propagate cancellation and use a finite deadline. A preview
cannot outlive recent authorization, the session, company policy or known
maintenance. The selected dates are copied before acquisition. Data mapping
rejects contradictory date partitions, totals, years and remaining balances.
The preview carries a revision of the selected policy, working-day plan and
annual balances. Submission retains that revision, allowing the server to reject
a changed calculation before reserving days. Preparation and submission are
domain/data boundaries consumed by the feature-owned creation form.

## Unfinished local input

The creation form keeps one working copy per API audience, account, company and
employment. Dates, selected type metadata, portion, reason and exact document
revision metadata survive process replacement without an age limit. Opening or
editing acquired input does not require renewed credentials. Choosing new catalog
entries, calculating charges and submitting still require current online review.

`OpenLeaveWorkingCopy`, `SaveLeaveWorkingCopy` and `DiscardLeaveWorkingCopy`
coordinate the local copy, Leave admission/directory, session and workspace
repository contracts. They require locally acquired employment ownership and
honor confirmed access denials. Ordinary credential age does not hide the copy;
a replacement account/company or removed employment cannot expose it. The form
requests one bounded synchronization if its owned employment directory has not
been acquired yet. It does not poll until a draft becomes available.

The data repository authenticates a bounded versioned envelope in
`leave-replica/<company>/working/<employee>`. A shared key-creation lock prevents
autosave and replica acquisition from creating competing credentials. Clearing
ordinary Leave facts does not remove unfinished input. Compare-and-set revisions
reject an older editor; repeating an already committed mutation acknowledges only
the same stable input. Missing keys and malformed data remain storage failures.

The route owns one serial writer, a 250 ms delay for waiting input, one pending
mutation and the latest edit. Edits received during a save coalesce; they do not
cancel the admitted write. A failed save retains its mutation for explicit retry.
The form shows local save status and requires confirmation before replacing or
discarding input. Back flushes the latest edit and offers an explicit choice if
saving fails. A lifecycle stop flushes once; final owner disposal cancels its work.
A denial stops the writer and hides private input, including during a failed retry.

## Durable submission

`PrepareLeaveSubmission` admits an unexpired owner preview, a bounded reason and
document-revision references, then stores one immutable command per employee.
The operation and request IDs are generated once. Repeated confirmation restores
that command; changed dates, reason, evidence or preview cannot replace it.
Missing required evidence is a use-case validation failure. The form first flushes
local edits; preparation verifies that the acknowledged working revision matches
the reviewed dates, portion, reason and evidence. The working revision is local
command metadata and does not change the HTTP request body.

Submission has its own encrypted partition and repository contract, sharing raw
command storage and its credential with existing request actions. The original
selection, preview revision, reason and evidence are authenticated separately
from the outcome. Session replacement for the same account can recover them;
another company/account cannot. A missing key never overwrites retained data.

`ReplayLeaveSubmission` serializes manual and background delivery, checks fresh
company policy, and persists an unconfirmed attempt before making the HTTP call.
Retries use exactly the same request ID, operation ID, payload and preview revision.
A creation receipt must identify that request at version zero. Delivery is bounded
to 30 seconds, ten sends per batch and three automatic attempts per command.
Cancellation and a lost response retain the original command for explicit recovery.

An unsent or definitely rejected command can be removed using its observed
revision. A confirmed command first needs canonical request/cache reconciliation.
An ambiguous outcome cannot be discarded as though the server rolled back, and a
late rejection cannot disprove an earlier uncertain attempt. Competing removal and
replay use storage revision checks; stale work cannot replace the retained outcome.
Canonical confirmation removes only the command's matching working revision before
retiring the command. A newer draft survives. Failed cleanup retains the command;
unsent, rejected and uncertain delivery never silently discard working input.
Older retained commands without a working revision remain readable.

## Creation interface

The overview opens a route pinned to the visible employment and company date.
The form offers an effective-date leave catalog, controlled start/end calendars,
full or half-day portions, and a multiline reason. Changing the start date reloads
the catalog; changing dates, type or portion cancels an obsolete preview. The
backend decides working dates, charges and annual projections. The UI displays
excluded dates and never derives a balance from request history.

Confirmation persists the reviewed input before delivery and freezes further
editing. The screen distinguishes active sending from queued, uncertain, confirmed
and rejected outcomes. Retry restores the original IDs, dates, reason and preview
revision after page/process replacement. An uncertain delivery cannot be discarded.
Confirmed delivery refreshes the canonical request before retiring the command
and replacing the form with its detail route. Back returns to the overview.

The route owns lifecycle collection; the MVI owner cancels acquisition, command
observation and its preview-expiry timer when stopped. Current access failures
hide sensitive content and remain gated through failed retries. A completed command
source can reopen after explicit recovery and admitted acquisition. Unfinished form input
uses a separate local working copy; confirmed input uses immutable command storage.
Employees can select up to three published personal documents through the shared
[document catalog](documents.md). The picker owns its MVI state, bounded page,
request and read-deadline timer. Its navigation entry clears a dedicated
ViewModelStore when dismissed. Closing or replacing a dialog invalidates its
callbacks without changing the form's dates or reason.

The catalog refresh keeps still-admitted metadata visible while selection is
disabled. An expired or denied read stays gated through failed refreshes. Selecting
another page replaces the previous page; the UI never accumulates an unbounded
list. The original document revisions become part of the immutable submission;
the server independently rechecks their current availability. Removing the last
required attachment disables confirmation. A retained submission shows its
original attachment count after process restoration.

## Offline access

Request summaries, complete details, acquired activity pages and balances are
stored per resource ID in the account-owned
`leave-replica/<company>` partition. `SyncLeave` explicitly requests both Leave
collections, fetches current canonical resources and writes records, tombstones,
directory metadata and the next cursor in one transaction. A record version cannot
move backwards. Request reads use the owner-only endpoint; an ambiguous not-found
response is a failure, not an invented deletion.

A replacement bootstrap writes to a staging generation and publishes only after
change-feed catch-up. Initial individual acquisition can publish complete request
facts before the first feed finishes; it does not publish a completed overview.
Ordinary cursor expiry or reauthentication for the same account starts
a replacement while retaining published facts. Confirmed scope changes remove the
old projection before acquisition. A failed cleanup keeps its original authority
failure. The employment directory is bounded by the server's 200-employment sync
scope; each invocation handles at most ten feed pages, eight references per page,
four parallel canonical reads, and 30 seconds of work. Progress resumes on the
next invocation; it does not limit retained history to the last displayed page.

The replica has its own platform credential. Payload encryption binds API audience,
account, company, generation, ID, version, order and index tags. Keyed filter tokens
support bounded SQL/IndexedDB queries by employment and year/status. Key and plaintext
byte buffers are cleared after use. Cancellation is checked before admitting a
write; an admitted storage transaction remains atomic.

A separate encrypted read-admission record retains confirmed access, MFA and scope
denials. A process-owned write buffer keeps a denial observable while its durable
write is pending or fails. List, detail, preparation and retained-command reads
share that gate, including the preceding overview fallback. Removing ordinary
facts does not clear it. A fresh single-request read cannot reopen the collection.

Only complete synchronization started after the latest denial can reopen reads.
Publication uses the observed denial revision, so an older acquisition cannot
clear a newer restriction. A bounded partial bootstrap retains the gate until
catch-up; older activity backfill has its own progress. Detail and form retries
request this recovery only when a stored denial exists, then resume their own
operation. Ordinary offline reads and credential age do not trigger recovery.

The preceding session-bound overview remains readable as a migration fallback
until the first full replica publishes. Known empty replica results never restore
that older page. Credential renewal does not expire a saved fallback read.

Each request retains its header, up to 366 dates in chunks of 128, and independently
acquired activity pages. Summary and full current facts commit together. Component
counts, versions, ordering and index tags are authenticated. Queries select date
components by request version and require their live parent; stale chunks or
children of a deleted request cannot become a partial success.

After feed catch-up, the sync use case reads up to eight pending activity frontiers.
It reuses acquired pages or obtains older revisions, then persists each page and
its strictly decreasing frontier together. A version ahead of the stored parent
waits for feed publication. Failures and cancellation preserve progress. History
acquisition retains the header's original session, acquisition ID and timestamps;
it cannot renew an action review. Other request IDs and previously acquired pages
remain available after navigation or account reauthentication.

The previous single-request snapshot remains a read-only migration fallback until
the expanded format publishes. Format upgrades stage replacement records without
hiding the preceding overview or detail. An authoritative missing request cannot
fall back to old ciphertext. A missing encryption key cannot be regenerated over
existing records, including records retained for an inactive account.

Saved facts have a freshness timestamp, without an age-based read limit. Command
preparation separately requires current authorization and a reviewed version.
Confirmed authority loss removes content; transport failure retains owned views. Cancellation or a replaced workspace
cannot publish old results or clear the replacement scope. There is no automatic
retry, background polling or permission orchestration in the data layer.

## Presentation

The feature owns its route, MVI ViewModel, declarative page and filter dialogs.
The route collects state with lifecycle awareness and pairs start with
stop/disposal. Leaving cancels acquisition and observation. Replacing filters
cancels the preceding local query while background synchronization continues.
The ViewModel holds an authoritative read failure through
retry and resubscribes only after fresh publication. A failed local cleanup cannot
silently make restricted content visible again.
The gate also survives subsequent network or storage failures; those errors do
not authorize reading the previous view.
An upstream storage failure can complete the cache flow without ending the outer
state collector. A successful explicit refresh reopens observation in both the
overview and detail screens; errors never start an automatic retry loop.
Stale data remains visible during explicit refresh, retaining the current
employment/year/status or detail history selection. A network failure reports its
cause without expiring that content or starting a hidden retry loop.

Balances have their own year filter; request history explicitly covers all dates.
Year, status and paging controls remain usable during synchronization and after a transport failure. Cursor actions
accept only the visible next page or first page, and employment choices remain
limited to the account-owned directory. An active route retains its filters through
refresh and lifecycle stops. A new visit opens the company-local current year and
all request statuses from saved records. The migration fallback retains its earlier
selection until the replica is available. The screen shows the last successful
sync time and never infers a balance from request rows.

Detail history accepts only the visible next revision cursor or the latest page.
Returning to the latest page cancels an older pending read, and an unavailable
earlier page does not trap navigation. Viewing earlier revisions keeps the
server's current header rather than presenting an old status as current. An active
route retains its selection; a new visit starts with the latest stored activity.
The old migration snapshot can retain its original selection until replacement.
Cached page navigation remains available offline. A missing or revoked request
hides its saved content, including through subsequent failed retries.

## Durable request actions

Withdrawal, cancellation requests and withdrawal of a pending cancellation have
separate domain intents. Preparation checks the visible request version, recent
authorization, write permission, available actions and a reason of at most 1,000
characters. A repeated confirmation recovers the same retained intent; different
input cannot silently replace it.

The command repository coordinates raw command storage, authenticated encryption
and credentials. Request bodies and outcomes are sealed separately, with account,
API audience, company, request, purpose and revision authenticated. Commands
survive session replacement for the same account without becoming accessible to
another account or company. Losing a key never regenerates one over ciphertext.

Replay belongs to a singleton use case shared by manual delivery and the sync
worker. It rechecks the company policy and scope, records an unconfirmed attempt
before transport, and reuses the original operation ID, version and reason.
The server receipt must identify the request at exactly the next version. A
later denial cannot turn an earlier uncertain attempt into a known rejection.
Delivery has a 30-second deadline, at most ten sends per background batch and
three automatic attempts per command. Manual recovery remains available within
the bounded retained-attempt limit. There is no retry inside a datasource.

Explicit review can discard an unsent command with its observed revision. A
confirmed or definitely rejected command is retired only after canonical review
and cache reconciliation. A concurrent replay wins over an obsolete removal.
Unconfirmed outcomes cannot be discarded as though the server rolled back.
The detail page exposes only currently admitted actions. A controlled dialog
collects the reason; pending, unconfirmed, confirmed and rejected outcomes have
distinct recovery controls. Closing the dialog does not pretend that a retained
operation was rolled back. Leaving the page cancels its work and subscriptions;
returning restores the account-owned command. Confirmation refreshes the canonical
request and retires the command only after reconciliation. The screen never invents
a local approval or cancellation status.

A current denial gates the detail through subsequent failed retries and lifecycle
replacement. An old failure retained as command evidence does not override a new
successful read. A completed command-storage observer reopens only after an
explicit successful refresh. Duplicate confirmation, expired action reviews, changed request
versions and obsolete outcomes cannot replace the visible operation.

## Remaining workflow

Entitlement administration and team workflows remain separate from employee
self-service. Published evidence and local file transfer are covered by
[Documents](documents.md). A cached balance is never a reservation or authorization
to submit.

## Validation

The shared read-admission correction passed root checks, 210 targeted Chromium
tests and Android/Web builds. SQLite and IndexedDB cover durable denials, failed
writes, competing recovery, cancellation and company-bound encryption. API 35
acceptance retained a controlled denial across an offline process restart, then
reacquired data and reopened it offline with unchanged ciphertext. See the
[executed validation](validation.md#leave-read-admission).

The submission foundation passed complete root checks, 86 Leave domain/data
Chromium tests, Android dev assembly and the production Web build. New regression
coverage includes lost local-save and server responses, immutable retries,
manual/background contention, current permission checks, late scope changes,
finite attempts, failed receipt persistence and cancellation during encryption.
Real SQLite tests reopen the encrypted command across sessions and reject copied
envelopes, altered revisions, missing keys and mismatched creation receipts.
The development API preview was rechecked without changing its stored balance.

The preceding action interface passed root checks with 531 desktop tests, all
29 Leave presentation tests and both localization tests in Chromium, plus Android
dev and production Web builds. The command foundation's 51 domain/data Chromium tests passed in
the preceding increment. Coverage includes immutable duplicate admission, read
leases, pending cancellation, late receipts, account/company replacement,
manual/background serialization, finite deadlines, bounded attempts, failed
receipt storage and removal races. Real SQLite tests verify encrypted reopening,
replacement sessions, missing keys and tampered envelopes.

MVI and assembled UI tests cover denied reads, disposal, expired leases, both
cache/network arrival orders, response loss after a committed action, recovery
using the original operation, and withdrawal of the pending cancellation. Shared
layout tests cover Indonesian at 200% text, multiline reasons and fixed navigation.
The reusable field also passed the starter's root checks and Android/Web builds.

The API 35 emulator read a fictional allowance, filtered years/status, restored
cached content after an offline process restart and recovered online. A fictional
half-day request created through the development API was withdrawn from the mobile
page: its reservation returned to the available balance. Another approved request
was used to retain a cancellation offline, restart the process, restore the same
reason and send the saved command after reconnecting. Its pending cancellation
kept the consumed balance unchanged. Withdrawing the pending cancellation restored
the approved status without another ledger movement. Light/dark appearance,
200% text, multiline editing and dismissal were inspected on the emulator.
Earlier history pagination and lost-response
receipt recovery use automated transport fixtures; they are not represented as
physical-device checks.

The creation interface also passed complete root checks, 42 presentation tests
in Chromium and Android/production Web builds. An assembled UI test verifies one
creation after response loss and an unchanged replay. API 35 acceptance verified
server-calculated excluded dates, half-day charges, encrypted confirmation while
the API was blocked, restoration after process restart and explicit delivery after
reconnection. Withdrawal returned the test reservation to the available balance.

Evidence transfer and physical iOS acceptance remain separate work. No frame-time,
heap or battery measurement is claimed.
