# Employee expenses

Expense domain use cases admit company/session access and acquire employee
claim views. Data adapters map the API and maintain encrypted snapshots.
Domain contracts remain independent of presentation and platform SDKs.

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

## Validation

The expense domain and data modules passed 31 desktop and 25 Chromium tests.
They cover scope changes, cancellation, late results, bounded acquisition,
composite revisions, exact amounts, encrypted chunk integrity and failed cleanup.
