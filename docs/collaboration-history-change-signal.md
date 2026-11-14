# Atomic local history invalidation

Existing Group and Company history repositories commit effective source mutations together with
one account-owned signal through the existing ReplicaStore.mutate transaction. First insertion,
subsequent merges, edits, redactions and local hiding retain both facts or neither. A competing
source/signal checkpoint returns false without a partial publication. Lost replies remain failures;
cancellation propagates. Exact replays retain their original ownership checks and generate no wake.

The signal has a fixed protocol marker and fresh opaque generation. Its raw owned partition has no
message, account identity, provider status or secret payload. It is an invalidation hint, never
content authentication, admission or repair-completion evidence. Malformed existing metadata fails
instead of being silently replaced. Existing encrypted history keeps its codec and key ownership.

StoredCollaborationHistorySignalRepository observes one published checkpoint through safeStorageFlow,
without key acquisition. Groups of the same account can receive the same coarse generation;
Company content can arrive before canonical binding. Owning use cases must enforce Group access
and exact canonical source selection. No timer, transport or per-room observer is introduced.

Thirteen shared actual-storage methods cover absence, login/reopen, pre-binding Company content,
replay/hiding, edits/redactions, competing source/signal writes, uncertain replies, cancellation
before/after commit, account replacement, malformed metadata and a live observer's committed source
visibility/disposal. Eighteen old fault-injection overrides now target the actual atomic mutation;
All 22 Company and 16 Group history methods, plus 41 delete/edit methods, remain. The assembled
application resolves the new repository contract. Focused125/full16924 preserve all16898 earlier cases plus26 executions.
Apple test compilation is separate from actual Apple execution, which remains outstanding.

The existing repair owner still needs the admitted catalog/history reader, durable captured source
windows, unresolved handoff and fair continuation across turn budgets. Completed demand must retain
its captured source signal/catalog version so unchanged reopen does not restart the entire scan.
Old content and first canonical binding need startup/catalog recovery.

The first full run failed eight delete/edit tests that injected faults at the former single commit
operation. Correcting 11 interceptors in three shared contracts preserved all 41 methods; production
code was unchanged by that correction. Its failed result and original focused84 evidence remain.

No UI, native graph, phone
release or live deployment is activated by this storage capability. This HRIS signal stays downstream;
the generic atomic storage and bounded change APIs are already independently ported to Starter.

Evidence: `docs/measurements/collaboration-history-signal-2026-11-14.json`.
