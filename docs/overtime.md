# Overtime

Overtime belongs to Workforce, alongside calendars and attendance. Employees can
open their own requests from Home or All services, filter by month and status,
and inspect a request's planned/actual time, approval progress and activity.
Planning opens a separate form with the reviewed work schedule and company-local
date/time input. Request details expose admitted actual-time submission and withdrawal
through separate confirmation forms.

## Scope and acquisition

`GET /workforce/me/employments` returns the current account's minimal employment
references. Overtime accepts `overtime.self.manage`, `overtime.read`,
`overtime.manage` or `workforce.read`; discovery never grants calendar or profile
access. Workforce availability remains independent of People.

`RefreshOvertime` acquires current company policy, owned employments and one page
of requests. The initial period is the server's company-local month. Later periods
span at most 62 days. Requests are bounded to 20 rows with explicit continuation.
The overview stores display summaries rather than all request bodies.

`RefreshOvertimeRequest` independently verifies the selected owned employment
before acquiring details. It returns the frozen schedule, requested/actual/approved
time, current approval progress and ten history entries. History continuation does
not replace current request facts. Approver directories are validated during
mapping but are not retained in the employee read view.

Both use cases own a 30-second acquisition deadline, cancellation, scope checks
after I/O and atomic publication. Repositories map raw datasource values and
technical failures. Datasources choose no access, retry or subscription policy.
Stale displayed content remains readable during refresh or network failure.
Read observation has no timer that clears the page. Planning and action
confirmation still require separate reviewed input and explicit user actions.

## Offline reads

The overview and most recently selected request occupy separate bounded encrypted
partitions. Each plaintext representation is limited to 96 KB. Workspace admission
owns their encryption key; a reader cannot replace a missing key. Authenticated
context binds session, account, audience, company, generation and view purpose.
Compare-and-set rejects an obsolete publication.

Local facts remain readable after their freshness timestamp. Recent authorization
bounds planning and action reviews, independently of the saved read model. Account
or company replacement cancels observation. Confirmed revocation remains
authoritative if local cleanup fails.
Presentation must retain that gate through pending or failed recovery and reopen
observation only after fresh admitted acquisition succeeds.

## Presentation

The overview and request have separate feature routes and MVI owners. Route
disposal cancels acquisition and observation; an account or company replacement
removes their content. Selected employment and continuation travel with the
request route so details independently verify their ownership.

The top bar and month/status filters stay above the scrolling list. Refresh
failures remain visible beneath those controls, including when the request is
scrolled. Refresh uses the existing header action. Month selection opens a
controlled dialog; pagination never implies that a partial page is a total.

Company-local dates distinguish the work date from an overnight interval.
Details retain the schedule reviewed for that request, even if the current
calendar changes. UI formatters localize dates, durations, statuses and failures;
pages do not calculate approved time or acquire data.

Authoritative read failures retain their gate through pending and failed retries,
even when cleanup leaves ciphertext behind. A successful admitted refresh reopens
an observation that ended with a storage-flow failure. Replacing the shared cache
with another selection clears the obsolete view instead of retaining its expired
lease. Navigation and mutation outcomes are not inferred from a failed refresh.

## Planning preparation

`ReviewOvertimeSchedule` acquires live company policy, the account's minimal
employment directory and the selected day's server review. A management grant
does not broaden the native picker to another account's employment. Review facts
retain the employment version, company-local date, timezone, resolved schedule
and content revision used by the eventual plan.

The preparation lasts at most five minutes, capped by the policy deadline,
session, workspace admission and upcoming maintenance. Acquisition has a
30-second deadline and checks ownership after I/O. Leaving its owner discards
a cancelled late result. Preparation creates no request or durable command.

Pure application policies derive worked duration from whole UTC minutes and the
declared break. They enforce the 12-hour interval limit, company work-date window,
shift exclusion, actual-time bounds and completed-work requirement. Datasources
only acquire DTOs; repository mapping validates protocol consistency and preserves
localizable server failures.

## Durable commands

Planning, actual-time submission and withdrawal have typed immutable commands.
`PrepareOvertimePlan` validates the reviewed schedule before local insertion;
`PrepareOvertimeAction` uses admitted request facts and pins their observed version.
Neither sends HTTP. Competing confirmations recover the canonical stored input.
One planning command is retained per employment; actual time and withdrawal share
one slot per request so conflicting actions cannot replace each other.

The data repository stores separate authenticated input and outcome envelopes in
the account-owned command store. Associated data binds account, audience, company,
operation, resource and outcome revision. Input is limited to 16 KB and outcome
to 4 KB. Reads return at most 50 records. Lost keys are never replaced over retained
ciphertext, and cancellation clears owned plaintext/key buffers before publication.

`ReplayOvertimeCommands` is one shared manual/background `SyncTask`. A 30-second
deadline includes waiting for its mutex. Each delivery reacquires current company
policy, persists an unconfirmed attempt before transport, and reuses the original
operation key, body, employment/request version and planning revision. Worker runs
scan at most three pages and send at most ten commands; automatic attempts stop
after three, leaving explicit recovery available. A command permits at most 1,000
total attempts.

A matching receipt confirms delivery. A documented first-attempt rejection can be
reviewed and discarded; a later rejection cannot disprove an earlier uncertain
commit. Unconfirmed input stays retained. `ReviewOvertimeCommand` removes confirmed
evidence only after admitted request facts reach its receipt version, and its
compare-and-set cannot remove a concurrent delivery. Repositories perform no retry,
permission or UI orchestration.

## Planning form

The planning route owns typed form events, one schedule acquisition, one command
observation and the review-expiry timer. Leaving cancels these resources; late
responses cannot publish into a later entry. A successful explicit review reopens
completed storage observation, while known access failures remain gated throughout
pending or failed recovery.

Date and time controls use the company's IANA timezone. Domain policy resolves
local input to whole UTC minutes on the computation dispatcher. Nonexistent or
ambiguous DST times are rejected with localized validation; an unchanged original
instant can retain its known offset when preparing actual time. The reusable
`AppTimeInput` only renders controlled numeric fields.

Submission freezes reviewed input before delivery. Pending or unconfirmed input
restores without acquiring a replacement schedule. Unknown outcomes offer retry
of that saved request; rejected input requires explicit discard confirmation.
Confirmed commands remain reviewable when detail acquisition fails. After admitted
request refresh and revision-checked cleanup, navigation opens the request.

The compact top bar and primary action stay outside the scrolling form. Refresh
keeps the overview action in place but disables it until acquisition completes.
An expired review preserves the form and asks for an explicit schedule refresh.

## Actual time and withdrawal

Request details use the server's allowed actions and the current company grants.
Actual time opens a form initialized from the planned interval; employees confirm
company-local dates, times, break minutes and a reason. Withdrawal has a separate
reason and confirmation action. Both pages keep Back and the primary action outside
their scrolling content. Opening either page creates no command.

The action route owns its MVI state, one combined request/command observation and
one acquisition or delivery job. A request revision change blocks an existing draft
until the employee returns to the current request and opens a new form. Preparation
uses the domain action policy and freezes the observed version before transport.
UI date controls constrain selection to the planned dates; domain validation still
enforces actual interval bounds, duration and completed work.

Both actions share the retained request-command slot. Entering withdrawal while an
actual-time outcome is uncertain restores that original action, including its input
and operation key. Retry cannot replace it with a withdrawal. A confirmed command
stays reviewable until admitted details and revision-checked cleanup succeed;
background confirmation alone does not navigate the page.

Back and route stop cancel observation and pending work. Late responses cannot
replace a later entry. Explicit successful acquisition reopens completed storage
observation; known access failures stay gated through pending or failed recovery,
even if local deletion fails.

## Validation

The focused desktop run passed 41 Workforce domain and 25 data tests, including
23 new overtime cases. They cover ownership, invalid protocol facts, finite
acquisition, late cancellation, failed cleanup, lease expiry, encrypted SQLite
restoration, competing publication and cross-scope ciphertext rejection. The full
headless workspace `:check` and Workforce domain/data `wasmJsBrowserTest` targets
also passed. The data browser tests register the same IANA time-zone database as
the application host; their dependency and lockfile are included in CI.

Presentation integration passed the root checks with 805 desktop tests, all 21
Workforce presentation Chromium tests and Android dev assembly. The new MVI cases
cover replacement, late cancellation, denial recovery, expired reads, completed
storage subscriptions and offline filter restoration. Layout tests cover fixed
controls and visible refresh feedback, Indonesian dark appearance at 200% text,
month selection and history recovery. The assembled application test uses the
HTTP adapter, encrypted cache and feature routes together.

API 35 emulator checks covered the development API, month/status filters,
overnight dates, offline process restoration and explicit recovery. The validated
update was installed on the authorized phone with `--install-only`. See the
[validation record](validation.md#overtime-history-and-request-details).

Planning preparation passed root checks with 825 desktop tests, 56 Workforce
domain and 17 data Chromium cases, and Android dev assembly. Twenty new cases
cover owner discovery, read-only grants, late scope changes and cancellation,
review expiry, bounded transport, malformed facts, overnight shifts, DST boundaries
and actual-time limits. This boundary increment did not add a new device flow.

Durable commands passed root checks with 868 desktop tests, 85 Workforce domain
and 22 data Chromium cases, and Android dev assembly. Forty-three new cases cover
competing preparation, immutable replay, lost responses, scope replacement, finite
deadlines/retries, cancellation, reconciled cleanup, encrypted SQLite restoration,
tampering, cross-scope denial and bounded transport/storage. This increment adds
command contracts and delivery; it does not claim new device interaction.

The planning form passed root checks with 887 desktop tests, 89 Workforce domain
and 31 presentation Chromium cases, and Android dev assembly. Nineteen new cases
cover local-time/DST resolution, controlled input, review expiry, pending
cancellation, retained denials, completed storage observation and original-command
recovery. The assembled application test loses a successful server response and
verifies identical replay through the real transport adapter and encrypted store.
API 35 acceptance verified a real plan, blocked transport, offline process
restoration and explicit replay. See the
[planning validation](validation.md#overtime-planning-and-delivery).

Actual-time and withdrawal forms passed root checks with 903 desktop tests, five
shared-presentation and 40 Workforce presentation Chromium cases, Android dev
assembly and application Web compilation. Sixteen new cases cover immutable
action recovery, observed-version changes, cancellation, repeated Back, retained
denials, storage-flow completion, large-text layouts and typed route restoration.
The assembled application loses a successful actual-time response, recovers its
original command from the withdrawal entry and then confirms a separate withdrawal.
See the [action validation](validation.md#overtime-actual-time-and-withdrawal).
