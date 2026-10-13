# Employee expenses

The expense feature has separate domain, data and presentation modules. Domain
use cases admit company/session access and acquire employee claim views. Data
adapters map the API and maintain encrypted snapshots. Routes own MVI state,
filters, observation and acquisition lifetime.

## Reads

The employee directory uses `/expenses/me/employments`, independently of People
permissions. Every claim, history, submission and payment request explicitly
uses `scope=SELF`, including for accounts with finance permissions. The list uses
company-local creation months, an owned employment, optional status and bounded
cursor pages. Transaction dates remain individual expense-line facts.

Detail acquisition reads the claim before and after its history, latest
submission and payment progress. A changed claim or inconsistent revision
returns a conflict without publishing a partial view or silently retrying.
Amounts remain exact decimal domain values; approval does not imply payment.

## Offline access

List and detail snapshots have independent, bounded partitions. Authenticated
encryption binds account, session, API audience, company, generation, view and
chunk identity. Bounded chunks fit the database record limits and publish in
one compare-and-set transaction. Missing or reordered chunks cannot restore a
partial snapshot. Temporary keys and plaintext buffers are cleared on exit.

Snapshots expire within one hour and no later than their admitted workspace,
session or scheduled maintenance boundary. Changes to identity, permissions or
company timezone invalidate an old view. Transport failure can retain admitted
content. Known access and MFA failures remain gated through pending/failed
retries, including when cache cleanup fails; a fresh admitted acquisition must
succeed before observation reopens. There is no automatic retry loop.

## Pages

Expenses opens from All services. Month and status filters stay above the
scrolling list; multiple employments use a separate selection dialog. Detail
groups amounts, expense lines, submitted receipt metadata, approval, payment
progress and paged activity. Back and refresh remain in the fixed app bar.
The pages support English, Indonesian and large text.

Each screen registers separate typed events. Changing filters cancels obsolete
acquisition; leaving the route stops acquisition and observation. Only visible
claim IDs and issued cursors can trigger navigation or paging. Explicit refresh
can recover an ended storage subscription.

The current native increment provides reads and offline views. Draft editing,
receipt selection/export, submission and subsequent employee actions are being
connected separately to the backend's versioned, idempotent command contracts.

## Validation

The workspace checks passed with 1,004 desktop tests. The expense modules also
passed 36 Chromium tests; Android dev assembly and production Web compilation
succeeded. Coverage includes scope changes, cancellation, mixed revisions,
exact amounts, encrypted chunk integrity, failed cleanup and 200% text.

API 35 emulator acceptance used claims created through the development API.
It verified month/status filters, detail and activity, approval without payment,
both themes, 200% text, encrypted list/detail restoration after background
process death offline, and explicit recovery after reconnection.
