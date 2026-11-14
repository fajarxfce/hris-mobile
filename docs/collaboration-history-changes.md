# Authenticated incremental history references

The existing encrypted Group and Company history repositories expose bounded change pages through
separate contracts. Each page consumes at most16 published raw records from one coherent local
read, then authenticates and maps content with the existing codec/key lifetime. Missing partitions
and generation restarts require no content key. Acquisition has no internal retry or observer.

The cursor retains stable audience/account, explicit Company/Group identity, organization,
conversation, room, generation and local position. A renewed login can reconstruct the current raw
handle only for that same stable source. Cross-account/organization/room reuse and malformed
generation/position values fail. Actual Company coordinates are never inferred from Group UUIDs.

Originals include hidden/redacted records. Aliases, edit evidence and edit revisions retain their
event or target references. Every non-null record is decoded and authenticated before selection;
tags cannot suppress that verification. Hiding rules are authenticated but need no standalone
reference because existing originals change in the same hide transaction and future imports apply
the retained rules. Only auxiliary revision rows may be raw null tombstones; they receive explicit
structural checks, not a false cryptographic payload authentication claim. Null originals, unknown
record kinds, invalid ciphertext/tags and missing keys fail without creating replacement keys.

Empty references may still have a next page. Window exhaustion is raw progress only. Owners must
confirm admitted index publication or durable unresolved handoff before completing an interval.
The completion boundary is window.throughRevision, not the potentially newer sourceRevision read
checkpoint. Current account/Group denial must also be checked after suspension by the use case.

Fifteen shared actual-storage methods cover both sources, bounded single reads, finite concurrent
writes, exact replay, hidden/redacted content, edits and removed revisions, auxiliary-only pages,
reauthentication/reopen, scope mismatch, generation replacement, corrupt content/tags, missing
keys, unsupported tombstones and joined cancellation. Focused55/full16898 retain all16868 prior
cases plus30 desktop/browser executions. The assembled application resolves each new contract to
the same existing repository instance. Apple test compilation is separate from actual execution.

No app owner, transport, UI or phone release was activated here. Admitted enumeration, durable
source progress and unresolved handoff still need integration under existing incoming/repair
ownership. Already-materialized incoming references must not be completed before index work.
Keep the generic raw implementation in Starter; this HRIS content mapping stays downstream.

Evidence: `docs/measurements/collaboration-history-changes-2026-11-13.json`.
