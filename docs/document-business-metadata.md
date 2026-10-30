# Document category and identity metadata

The Platform contract is implemented in `27e7f77e`; see its
[versioned metadata API](https://github.com/fajarxfce/hris-platform/blob/feat/firebase-auth/docs/document-business-metadata.md).
Mobile implements the owned read projection, encrypted format upgrade and
capability-aware initial upload. Correction/history screens and durable metadata
commands remain next. This is HRIS business behavior and has no Starter port.

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

Next: owned metadata details, bounded history, observed-version corrections and
durable recovery of the original metadata command. Then validate actual owned
emulator/API/storage behavior and final Apple/full native CI. Physical iPhone testing
remains waived. Neither `documents.02` nor `people.03`, and not the full PRD goal,
is accepted by these increments. No Starter implementation port is required. The
private schema-101 API and installed phone remain unchanged.
