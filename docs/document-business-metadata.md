# Document category and identity metadata

The Platform contract is implemented in `27e7f77e`; see its
[versioned metadata API](https://github.com/fajarxfce/hris-platform/blob/feat/firebase-auth/docs/document-business-metadata.md).
Mobile implements the owned read projection, encrypted format upgrade and
capability-aware initial upload. The separate header/history/correction transport
is also locally validated, as are the encrypted detail/history cache and per-resource
read-admission store. Admitted read orchestration and the detail/history screen
also pass local validation. Immutable correction contracts and encrypted command
storage are validated. Explicit intake, scoped observation, delivery and review also
pass full local validation, together with encrypted mutable working-copy storage.
Working-copy orchestration and the editor remain next. This is HRIS business behavior and has no Starter port.

`DocumentBusinessMetadata` separates category, document number, issuing country,
issue date and business-validity date from binary evidence. Dates are calendar
values, with the server's 1900–2200 bounds; expired evidence remains readable.
Nationality and tax residency stay independent. The mapper validates the complete
advertised value and revision; an old API's omission stays unknown/unsupported.
It never invents a default category for previously downloaded unknown fields.
Identity values are redacted from model diagnostic strings.

The owned `DocumentCatalogPage` retains complete records, including the document
version, business metadata revision and support flag. Finance/Leave still receive
`DocumentEvidence`; downloads select that nested immutable content proof. No
identity number enters those unrelated projections or the catalog's indexes.

Record format 2 uses the existing encrypted checkpoint, partition, key and
associated-data scheme. Format 1 records remain readable. Synchronization starts
a separate generation to hydrate all canonical records even when their document
versions did not change. The old published generation remains visible until the
replacement catches up. Cancellation, transport failure and restart retain the
staging generation and published facts. A confirmed admission denial still fences
local reads when cleanup fails; only a subsequent admitted acquisition reopens it.

Scoped employment discovery advertises metadata support. A change in that flag
also rebuilds the owned projection, including when this app previously upgraded
against an old API. Mixed capability pages or canonical records cannot publish an
incomplete replacement. A current, fresh format can still skip ordinary route
acquisition; a format upgrade bypasses that optimization. Explicit refresh checks
the current server capability. Existing page, parallelism and request deadlines
remain bounded, and no added timer or automatic retry owns these changes.

The old mapper's metadata loss was reproduced by one HTTP-adapter/cache-encoding
test at `0403faf5`. Root `:check` now passes all 1,869 desktop cases, including 83
Document domain, 40 data and three presentation cases. Both Document Chromium
suites pass all 123 cases, and the development APK builds. Root check and APK
assembly completed in the 160-second combined invocation; the subsequent npm
lock check required recording the new direct calendar-date dependency. The final
23-second browser run updated only the Document domain/test entries for existing
`@js-joda/core` 3.2.0 and passed. Production code did not change between these runs.

Nine new desktop scenarios cover actual transport retention, malformed metadata,
legacy omission/capability, same-version upgrade, interrupted replacement,
server capability transition, mixed responses, persistent denial and real encrypted
storage across restart. The storage contract also runs against browser IndexedDB.
The old index-page and app-layout fixtures were adapted to complete catalog records;
the migration fixture's setup was extracted to satisfy the method-length check.
Wrong task and missing SDK environment attempts executed no tests. Exact counts,
source and APK hashes, failure history and boundaries are retained in
[measurements](measurements/document-metadata-read-model-2026-10-30.json).

## Initial upload — locally validated

Upload preparation accepts optional editable business facts only when scoped
employment discovery advertises support. Pure domain policy trims printed values,
normalizes ISO country codes, validates canonical dates and freezes typed metadata
with the original upload intent. The 249 accepted country codes match JDK 21's ISO
list. Optional values become null; historical expiry remains valid evidence.
An old server receives the old omitted field, and old encrypted format-1 uploads
keep their original body and operation. No existing intent can be changed by edits
or an uncertain delivery. The same encrypted ownership, keys and associated data
apply after restoring the session or repository.

The English/Indonesian form separates access classification from the eight document
categories, accepts document number/country/printed dates, and displays retained
facts with upload status. Typed MVI events preserve unsent edits across route
attachment and close access after denial. A failed confirmation moves the form
to its retained feedback: the first assembled exercise reproduced an error hidden
above the long form, and the corrected test requires it to be immediately visible.

Final root `:check`, Android dev assembly and both Document Chromium suites pass
in 163.698 seconds. Reports contain 1,878 desktop and 131 Document browser tests,
with no failures/errors/skips. Nine new desktop cases cover validation, unsupported
servers, original retries/cancellation, real encrypted legacy/new intent restoration,
wire bodies and the assembled form/upload; the route-detachment case also now covers
metadata edits. Narrow upload and retained-details screenshots were inspected.
Earlier attempts retained a missing-argument test compilation error, two malformed
fixture responses and the actual error-visibility reproduction. See
[upload measurements](measurements/document-upload-metadata-2026-10-30.json).
The APK is built, but has not been installed; the private API remains schema 101.

## First rejected upload — locally validated

A reviewed server rejection on the first persisted Start attempt proves that this
upload did not commit. Its file and confirmed input remain stored while the user
reviews a fresh form. Explicit confirmation replaces that exact observed intent
with new document/revision/operation IDs by compare-and-set; encrypted bytes and
their proof stay intact. Ordinary upload updates still require the original intent.
A known rejected operation is not resent automatically or by the old resume action.
The user may also remove that local file after normal owned-read admission.

An unknown code, transport failure, or rejection after an earlier uncertain attempt
cannot release the original operation. Persisted attempt counts retain that distinction
across process recreation. Competing confirmations and stale writers cannot replace
a newer intent; cancellation after persistence leaves the new intent recoverable.
If the server stops advertising metadata support, previously confirmed metadata is
retained and cannot silently disappear from a replacement. Read denials continue to
hide the form until admitted revalidation; reopening alone sends no upload.

Two genuine preparation/reconfirmation failures at `d3888b9f` now pass. Two baseline
controls already kept unknown/previously uncertain commands pinned. Final root
`:check`, development APK and both Document Chromium suites pass in 189.354 seconds:
1,888 desktop tests and 140 Document browser cases, all without failures/errors/skips.
Ten new desktop cases cover initial rejection, reconfirmation, transport uncertainty,
concurrency, cancellation, capability loss, real encrypted storage and the assembled
application. The storage contract also runs on IndexedDB. The existing denial flow
now verifies an admitted editable form and no automatic resend. A method-length
check initially required extracting the existing unchanged-file validation into a
pure policy; the rule was not relaxed. The recovered-upload screenshot was inspected.
See [rejection measurements](measurements/document-upload-rejection-2026-10-30.json).

## Header, history and correction transport — locally validated

`DocumentMetadataRepository` acquires a header independently of published binary
evidence, reads twenty ascending immutable revisions per request, and sends a
confirmed correction with its original document version, metadata, reason and
idempotency key. A valid original receipt must match that document and increment
the observed version by exactly one; it is not the current header after later edits.
The adapter carries the active request session and bounds every response body.

The header preserves an older API's unknown metadata and unsupported capability.
History includes the initial revision's possibly unknown actor/reason without
inventing a verification event. Mappers reject wrong documents, private HR-only
classification in the employee model, discontinuous/duplicate history, invalid
metadata, noncanonical cursors and mismatched receipts. Cancellation propagates,
server denials retain their classifications, and private server diagnostics and
identity/reason values do not enter model diagnostic strings. Shared content
evidence and existing upload requests are unchanged.

Eight adapter and two domain cases pass, including actual Ktor request bodies,
request-session attributes, replay fields, legacy omission and byte limits. The
complete root check, development APK and both Document browser suites pass in
184.233 seconds: 1,898 desktop and 150 browser tests, without failures/errors/skips.
The first seven adapter cases also passed a focused 36-second run. These are new
feature contracts, not a claimed pre-fix reproduction. See
[transport measurements](measurements/document-metadata-remote-2026-10-30.json).

## Metadata storage and read admission — locally validated

The dedicated account/audience/company/document partition keeps its current header
and contiguous acquired-history position in an encrypted checkpoint. Each immutable
history revision has its own authenticated record. An initial replacement and later
conditional commits change the header with at most twenty rows; opening a page
decrypts one checkpoint and at most twenty selected rows. This supports all 1,000
allowed snapshots without a whole-history replacement or unbounded read. Previously
acquired pages remain readable across process restart, session replacement and
credential age. Legacy unknown facts can upgrade, while capability loss cannot erase
known metadata. Current facts cannot regress to a later-retrieved original receipt.

The cache uses the already provisioned workspace content key, reads it without
creating a replacement, and binds ciphertext to account, audience, company, document,
generation and record version. Writes compare the complete observed checkpoint;
stale writers, late account changes, gaps, conflicting immutable rows and regressing
versions across page boundaries cannot commit. Cancellation before the atomic write
retains the previous snapshot. Missing keys and manipulated ciphertext fail closed.

Per-document read admission is separate from the retained facts. A process-owned
buffer makes a confirmed denial visible during a pending or failed durable write.
Persisted denials survive restart and reauthentication. Only clearing the exact
observed admission revision can reopen the resource; a later denial wins over an
older pending admission. The read use cases apply this gate together with current
company/catalog admission.

Three domain and eleven real-storage scenarios pass on desktop and Chromium,
including SQLite/IndexedDB restart, all 1,000 history rows, scoped reads, lost keys,
ciphertext manipulation, competing writes, cancellation and denial persistence.
A measured page decrypts exactly 21 envelopes for its header and twenty rows.
Final root checks, dev APK and Document browser suites pass in 184.621 seconds:
1,912 desktop and 164 browser tests, without failures/errors/skips. The focused run
passed 101 domain and 62 data cases in 39.013s before the final manipulation case.
One compiler-proven redundant nullable condition was removed; no failed feature
test or pre-fix reproduction is claimed. See
[storage measurements](measurements/document-metadata-cache-2026-10-30.json).

## Detail and history screen — locally validated

The catalog exposes a separate details action alongside its existing download action.
The route observes owned header/history facts locally, applying company/catalog and
per-document admission. It neither sends corrections nor expires local facts with
credentials. A finite acquisition checks the current session/workspace after waits,
fresh company policy, current owned employment (at most ten directory pages), matching
server capability and twenty contiguous history rows. It publishes by exact cache
and admission revision. Legacy servers do not receive an unsupported history request.

History displays the original immutable facts, timestamp, reason and available actor
information, separate from the latest header. Paging controls precede the history
cards. Route dismissal cancels acquisition and clears private view state. Pending or
failed retry cannot reopen a confirmed denial; successful revalidation also restarts
a storage observation that previously completed with an error. Resource-only success
cannot clear an unrelated catalog denial.

Two added tests reproduced success incorrectly reported when company policy expired
during the new cache/admission publication. Completion now checks that policy and
preserves an earlier denial. Thirteen domain cases and two assembled application
cases cover those reproductions, directory bounds, legacy support, scope changes,
cancellation, timeout, storage-source recovery, paging and offline access. The actual
screen exercises also retain denial through a pending/failed retry and cancel an
in-flight request on Back. Three narrow screenshots were visually inspected.

Final root checks, Android dev assembly and both Document Chromium suites pass in
197.437 seconds: 1,927 desktop and 177 browser tests, with no failures/errors/skips.
An earlier compile attempt required stable local nullable-property bindings; the
first full check required extracting the new route entry from a 109-line navigation
method. No quality threshold changed. The focused domain and assembled UI runs
passed in 34.334s and 108.773s. Exact cases, failures, source/APK and screenshot hashes
are retained in [read/UI evidence](measurements/document-metadata-read-ui-2026-10-30.json).

## Retained correction commands — storage locally validated

A confirmed correction freezes its account, audience, company, employment, title,
observed document version, metadata, exact reason and operation ID. Its durable
outcome distinguishes pending, unconfirmed, rejected and confirmed. Only a reviewed
first definitive rejection proves non-commit. A rejection after earlier uncertainty
keeps the original operation unresolved. Confirmed and rejected outcomes cannot be
rewritten by a later failure. The receipt is the original expected version plus one;
it never replaces a newer header or history.

The encrypted repository uses the existing shared CommandStore and command content
key: at most 128 commands per account across features and one command per document.
Authenticated request/outcome envelopes are bounded to 16/4 KiB of plaintext. They
bind the account, audience, company, document, operation, purpose and state revision.
Complete observed-record comparison and atomic revision checks protect updates and
removals; attempt counts cannot regress. Local facts have no credential-age expiry.
A lost key cannot replace ciphertext in this or another command partition. Failure
storage retains bounded technical fields and omits private server diagnostics.

Three domain and six actual storage scenarios pass, with SQLite and IndexedDB
reopening, session replacement, original receipts, shared capacity, resource slots,
competing writes, Unicode input, missing keys, manipulated/oversized ciphertext,
cancellation and account changes before insertion. Initial focused validation passed
116 domain and 69 data cases in 40.635s; final terminal-state assertions and one
additional domain case are included in the full run. Root quality, Android dev
assembly and Document Chromium suites pass in 193.331s: 1,936 desktop and 186 browser
cases, all without failures/errors/skips. These are new feature contracts, not a
claimed baseline failure. [Storage evidence](measurements/document-metadata-command-store-2026-10-30.json)
retains all case names and source/APK hashes.

## Correction orchestration — locally validated

Explicit confirmation checks current account/workspace ownership, edit permission,
read admission, reviewed header and canonical input before storing a command. It
sends no HTTP. History acquisition progress can change independently; an unchanged
reviewed header does not force another confirmation. A changed header, unsupported
metadata or occupied/capacity-limited command slot cannot silently replace the intent.

Manual delivery has a finite thirty-second budget and shares an execution mutex with
intake/review. Every attempt checks fresh company policy, current header ownership
and the owned employment directory before persisting uncertainty and calling HTTP.
It reuses the exact operation and body, including after a later header version.
Cancellation, timeout, scope changes or lost responses preserve the original command.
A response is stored separately from cached current facts; a later read denial remains
closed even when saving the outcome or admission fails. No automatic sync task sends
these commands.

Local command observation supports one document and the whole company. Resource
restrictions hide only the affected command; company/catalog denial closes the whole
projection. Observation does not fetch or expire pending facts, and dismissal releases
all subscriptions. Explicit review can discard unsent or definitively rejected input.
Uncertain input remains retained. A successful receipt can be acknowledged only after
admitted current metadata at least as new as its receipt has been acquired; newer
facts never regress to the original receipt.

Fourteen new domain scenarios cover confirmation, permission controls, slot/capacity,
header concurrency, retry uncertainty, later headers, receipt acknowledgement,
storage failures, ownership/MFA, cancellation, timeouts and resource subscriptions.
The first run's twelve failures were fixture setup: the inherited account had only
read permission and correctly rejected confirmation. The new write fixture now has
upload permission, and a separate read-only control remains denied. The focused retry
passed all 130 Document domain cases in 29.808s before the final overview scenario.
Full root checks, Android dev assembly and Document Chromium suites pass in 180.515s:
1,950 desktop and 200 browser tests, with no failures/errors/skips. These repository
fixture tests complement the separately accepted actual encrypted storage and Ktor
payload checks. See [orchestration evidence](measurements/document-metadata-command-flow-2026-10-30.json).

## Mutable drafts — storage locally validated

Each draft retains its reviewed header, editable fields, reason, generation and
revision. Bounded unfinished dates, country input and multiline reasons can remain
locally saved; confirmation still applies the separate strict business validation.
An ordinary draft save cannot replace the reviewed header. Explicit discard and a
new copy are required to start from another header, preventing an automatic rebase.

The existing ReplicaStore holds one authenticated checkpoint per document, using
the already provisioned workspace key without creating a replacement. The plaintext
is bounded to 16 KiB and binds account, audience, company, document, generation and
revision. Complete decoded-copy comparison and atomic checkpoint checks protect
writes/deletes. Copies reopen across database and session replacement, and credential
age does not expire them. Missing keys, altered ciphertext, late account changes and
cancelled encryption preserve the prior draft.

A company overview uses indexed checkpoint pages with twenty copies and one
lookahead. A measured full page decrypts exactly twenty envelopes; no entire-draft
inventory is read. Three pages cover all 45 test copies in stable document-ID order.
This storage path lets later UI expose drafts independently of the binary catalog.
The application use cases must apply catalog/resource admission before rendering.

Two domain and six actual SQLite/IndexedDB scenarios pass. Focused validation passed
133 domain and 75 data cases in 41.438s. Root quality, Android dev assembly and both
Document browser suites pass in 180.730s: 1,958 desktop and 208 browser cases, all
without failures/errors/skips. These new contracts do not claim a prior production
failure. [Draft storage evidence](measurements/document-metadata-working-store-2026-10-30.json)
retains exact cases and source/APK hashes. No new database primitive or Starter
implementation port was needed.

Next: admitted working-copy orchestration, matching-draft acknowledgement cleanup
and the versioned editor, with
durable recovery of the original metadata command. Then validate actual owned
emulator/API/storage behavior and final Apple/full native CI. Physical iPhone testing
remains waived. Neither `documents.02` nor `people.03`, and not the full PRD goal,
is accepted by these increments. No Starter implementation port is required. The
private schema-101 API and installed phone remain unchanged.
