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

## Request preparation

`LoadLeaveTypes` reads a bounded effective-date catalog and removes inactive
choices without changing its server cursor. `PreviewLeaveRequest` calls the
owner-only `/leave/me/requests/preview` endpoint. The response retains the policy,
charged days, excluded dates and separate annual balance projections. It does
not reserve a balance or create a request. Preparation requires `leave.self.manage`;
an HR read/manage permission does not broaden this employee workflow.

Both use cases acquire fresh company policy, recheck session/workspace ownership
after pending work, propagate cancellation and use a finite deadline. A preview
cannot outlive the current read lease, session, company policy or known
maintenance. The selected dates are copied before acquisition. Data mapping
rejects contradictory date partitions, totals, years and remaining balances.
The preview carries a revision of the selected policy, working-day plan and
annual balances. Submission retains that revision, allowing the server to reject
a changed calculation before reserving days. Preparation and submission are
domain/data boundaries consumed by the feature-owned creation form.

## Durable submission

`PrepareLeaveSubmission` admits an unexpired owner preview, a bounded reason and
document-revision references, then stores one immutable command per employee.
The operation and request IDs are generated once. Repeated confirmation restores
that command; changed dates, reason, evidence or preview cannot replace it.
Missing required evidence is a use-case validation failure.

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
hide sensitive content and remain gated through failed retries. A fresh admitted
catalog read can reopen a completed command source. Typed form values are
process-local until confirmation; confirmed input uses encrypted durable storage.
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

## Durable request actions

Withdrawal, cancellation requests and withdrawal of a pending cancellation have
separate domain intents. Admission checks the visible request version, its live
read lease, write permission, available actions and a reason of at most 1,000
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
explicit successful refresh. Duplicate confirmation, expired reads, changed request
versions and obsolete outcomes cannot replace the visible operation.

## Remaining workflow

Subsequent work adds new file acquisition and resumable document transfer.
A cached balance is never
a reservation or authorization to submit.

## Validation

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
