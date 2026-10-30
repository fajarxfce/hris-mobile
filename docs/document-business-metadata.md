# Document category and identity metadata

The Platform contract is implemented in `27e7f77e`; see its
[versioned metadata API](https://github.com/fajarxfce/hris-platform/blob/feat/firebase-auth/docs/document-business-metadata.md).
This Mobile increment implements the owned read projection and encrypted format
upgrade. Initial-upload fields, correction/history screens and durable metadata
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

Next, extend capability-aware upload preparation and the encrypted upload intent
without adding fields to an old omitted payload. Add category/identity/date input,
retained display, bounded history, observed-version correction and durable recovery
of the original command. Validate new submissions and uncertain retries, scope and
process replacement, then actual owned emulator/API/storage behavior. Apple builds
remain required; physical iPhone testing is waived. Neither `documents.02` nor
`people.03`, and not the full PRD goal, is accepted by this read-model increment.
