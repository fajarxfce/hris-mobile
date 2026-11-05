# Message edit storage and admission

An edit is a distinct decrypted Matrix replacement event, with its own event ID,
server timestamp, complete-content fingerprint and optional HRIS operation ID.
The original message keeps its text, content fingerprint, logical identity,
provider aliases, reply/forward context and ordering. These original fields still
supply send and receipt evidence. `latestEdit` is a separate display revision.

Admission requires the same sender, an exact retained original alias, unchanged
logical/reply/forward context, and an edit accepted before fifteen minutes from
the earliest retained original-alias server timestamp. A later retry alias cannot
extend that window. An edit cannot precede its actual target; it can precede a
later retry that was first observed as the primary row. Local hide
is independent; neither a valid edit nor a retry reopens a hidden or redacted message.

The decoder bounds raw text/UTF-8 bytes and nesting before parsing. It binds the
full event ID, sender and numeric timestamp to the detached SDK event. It accepts
plain-text m.replace/m.new_content, retains unknown fields in the canonical hash,
and rejects malformed known nested metadata. It never turns a replacement into an
original send receipt. Decoding alone does not establish room/account admission.

`mergeEdits` retains at most 16 replacements in one existing history checkpoint
transaction. The original alias, revision record and visible pointer share the
same owner/generation/revision check. Missing targets fail without creating keys;
ineligible edits leave the projection unchanged. Conflicting identities roll back
the entire batch. Replays are no-ops. Stale ownership or a competing checkpoint
cannot publish half a revision. Callers retain input and own acquisition/retry policy.

Revision ciphertext uses the existing owned history key and AAD, with opaque keyed
identities/indexes. Replacement aliases cannot be read as original event facts or
original send acknowledgements. Earlier revisions remain separately encrypted.
Redacting a revision tombstones its body and selects the newest retained valid
revision; redaction before acquisition prevents resurrection. An original redaction
clears its visible revision and all quote publication. It does not claim forensic
erasure of every old revision ciphertext or the SDK's independent event cache.

Replacement order is server timestamp then event ID. This is HRIS admission policy;
it is not a claim to reproduce Matrix SDK timeline-position aggregation. Fallback
reads 32 records per page, at most 16 pages, including equal-timestamp groups split
across opaque indexes. An exhausted bound fails atomically and retains the preceding
checkpoint for explicit recovery; it does not fabricate a latest revision.

Old history documents decode with absent optional edit fields. Their optional
firstAcceptedAt index is established from at most 128 retained original aliases on
the first mutation, under the same checkpoint. Later aliases can narrow the window
and trigger selection of an earlier admissible revision without changing primary
ordering or original receipt fields. Fallback seeks past newly expired revisions
before bounded paging. No database migration
or content-key replacement is needed. A pre-edit application cannot be assumed to
read new revision-bearing documents after an application downgrade. Quotes resolve
accepted revised text while original receipt comparisons remain unchanged.

The Android history source copies bounded `latestEditJson` separately from the
original JSON and immediately clears diagnostic model/string references. It
binds the replacement to its original native item and ignores malformed or
unsupported replacements without suppressing valid originals. Acquisition stores
original batches before edit batches and rechecks ownership/denial after every
suspension. Room/list UI renders only admitted persisted revisions. SDK edited
previews cannot replace an original before that admission.

[Validation](measurements/collaboration-message-edits-2026-11-05.json): 149 focused
cases passed in 358.931 seconds, including Android resource/identity tests,
acquisition cancellation/scope races, encrypted SQLite revisions and room
projection. Android/proof compilation, static analysis, formatting and
architecture passed. Full root/browser/Android checks cover 4,967 fresh accepted
cases across the two final attempts, with zero assertion failures/errors/skips
and zero Android lint issues. The first attempt's browser disconnected from
Karma; it was not accepted as a passing gate. The unchanged-source retry passed
in 88.562 seconds, executing all 175 presentation browser cases while successful
checks remained up-to-date. Earlier storage checks and the corrected earliest-
alias admission case are recorded separately in the measurement.

Provider traversal now resolves retained replacements through a separate
`getReplacementFact` read when its original-only reader has no match. The read
checks encrypted alias/revision/original identity under one checkpoint, including
redacted revisions. Reconciliation matches exact event ID, sender and server time,
then rechecks ownership and local denial before publishing. Already resolved
references stay resolved across offline reads; original send/read receipt and
quote APIs still exclude replacements. Unknown older edits remain unresolved until
acquired; this does not claim complete raw history recovery.

[Reference validation](measurements/collaboration-edit-references-2026-11-05.json):
122 focused cases pass in135.577s; full root/browser/Android/host/proof compile
checks pass in588.776s with4,981 fresh cases, zero failures/errors/skips and zero
Android lint issues. Tests cover offline age, repeated reads, exact identity,
redaction/restart, owner/denial/checkpoint races, cancellation and storage failures.
The new test's initial compile failure and stopped correction attempt are recorded.
No new device acceptance or edit authoring release is claimed here.

This increment is not delivered edit authoring or an edit outbox. Organization-wide
background acquisition, long-press editing with preserved ordinary drafts,
optimistic pending edits, native send/receipt recovery, and device proof remain.
The pinned SDK exposes originalJson/latestEditJson separately. Latest-only cold
recovery cannot reconstruct an earlier HRIS-valid edit hidden by a later invalid
edit. Typed `Timeline.editRevisions` and `Room.loadOrFetchEventWithRelations` omit
complete raw content/identity information needed for the current proof format;
never substitute their reconstructed content for original or replacement evidence.
