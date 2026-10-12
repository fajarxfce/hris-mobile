# Overtime

Overtime belongs to Workforce, alongside calendars and attendance. Employees can
open their own requests from Home or All services, filter by month and status,
and inspect a request's planned/actual time, approval progress and activity.
The native forms for planning, actual-time submission and withdrawal remain in progress.

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

## Offline reads

The overview and most recently selected request occupy separate bounded encrypted
partitions. Each plaintext representation is limited to 96 KB. Workspace admission
owns their encryption key; a reader cannot replace a missing key. Authenticated
context binds session, account, audience, company, generation and view purpose.
Compare-and-set rejects an obsolete publication.

Read leases last at most one hour and cannot exceed admitted access or scheduled
maintenance. Observations own one expiry timer, cancelled with their collector
or replaced scope. Revocation remains authoritative if local cleanup fails.
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
permission or UI orchestration. Native forms and real-API command acceptance remain
subsequent integration work.

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
