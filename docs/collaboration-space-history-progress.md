# Durable Space provider frontier and pending references

Space history traversal has its own typed local-access and provider-page repository contract.
Each encrypted checkpoint keeps the original physical provider company/device identity. A new
logical/native session can retain that original provider position; a different physical provider
cannot silently reset or continue it. Current authorization belongs to the acquiring use case.

The raw transaction engine atomically stores every metadata reference with its provider position,
including empty pages, while exact duplicate references keep their first sequence. Immutable
metadata conflicts reject the whole page. Compare-and-set guards prevent concurrent page writers
or reference resolution from discarding one another. Lost responses can be reconciled from the
committed position and references; replay never invents another message or provider transaction.

Provider head/until bounds remain independent of the pending-reference seek. A completed provider
wave may still contain undecryptable messages. A bounded pending pass keeps its original upper
reference count while new pages arrive; unresolved records remain indexed after that pass ends.
Its in-progress retry flag accumulates only within the pass. The future incoming worker must
retain independent durable retry work even when no new provider event arrives.

Company and Space repositories share one raw storage component; neither calls another repository.
Each repository maps storage errors and validates its typed provenance. Mapping remains inside
`safeStorageFlow`, so a malformed record emits a failure while later updates can recover through
the same observation. Session age does not invalidate account-owned local progress.

Existing Company checkpoint schemas1/2, authenticated byte frames and keyed indexes are preserved.
Space uses schema3 and a distinct authenticated namespace, with mandatory physical provider
identity. Provider tokens, original event IDs and metadata remain encrypted. Copying ciphertext
between Company/Space, devices or rooms cannot grant access. No database table migration is needed.

Thirteen new real-storage scenarios cover SQLite/IndexedDB/Apple adapters: restart and login
replacement, physical identity, exact old byte frames, bounded incoming passes, conflict/CAS,
response loss/cancellation, caller mutation, observation recovery, encrypted isolation, missing
keys and concurrent writes. Focused1/39028 completed94 cases in278.191s:13new+34old on each of desktop and actual Chromium.
The strict source/library/prior-case audit, formatting, architecture, detekt and iOS data-source
compilation pass. [Focused measurement](measurements/collaboration-space-history-progress-focused-2026-11-10.json).
Full1/92521 passed in746.742s. The strict audit verifies10,353 cases:8,334 freshly executed and
2,019 exact accepted controls, with no failures, errors or skips. Root check, Android host/release
compilation/lint, actual Chromium, iOS application source, proof APK/unit tests and quality checks
pass against the same4,787 source/library inputs as the focused gate.
[Full measurement](measurements/collaboration-space-history-progress-2026-11-10.json).
Actual native provider/storage acceptance remains pending. No product incoming consumer is enabled.
