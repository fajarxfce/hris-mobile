# Overtime

Overtime belongs to Workforce, alongside calendars and attendance. Its native
read contracts use the existing company API and shared schedule models. Employee
screens and durable commands are the next integration step.

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

## Validation

The focused desktop run passed 41 Workforce domain and 25 data tests, including
23 new overtime cases. They cover ownership, invalid protocol facts, finite
acquisition, late cancellation, failed cleanup, lease expiry, encrypted SQLite
restoration, competing publication and cross-scope ciphertext rejection. The full
headless workspace `:check` and Workforce domain/data `wasmJsBrowserTest` targets
also passed. The data browser tests register the same IANA time-zone database as
the application host; their dependency and lockfile are included in CI. Android
screen and API acceptance remain part of the subsequent presentation integration.
