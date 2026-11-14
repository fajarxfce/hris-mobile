# Checked history inbox publication receipts

History-to-inbox publication now returns the actual physical source version and committed index
version. A null result means contention; an operational or uncertain write remains a failure.
The writer derives the destination generation/revision from its prepared mutation after confirmed
success. It never reads the index afterward to guess which generation was written. A semantic
replay returns the checked existing versions without another write or history-signal update.

An explicit null projection checks the captured source/index pair for unresolved-only work.
Source and index absence remain distinct from contention and failure. An entirely absent pair
is confirmed with an atomic source-absence Check and conditional null-to-null index replacement,
without reading or creating content keys; confirming existing encrypted facts
still requires their keys. Scope and codec authentication remain in the existing repositories.

The admitted publisher returns the exact canonical Group/Company source owner and receipt with
its retained/unresolved event IDs. It now captures index metadata even when no originals resolve.
Domain policy validates the typed receipt and leaves the snapshot comparison token opaque.
No receipt alone completes incoming work: durable cursor/unresolved handoff is still required.

Twenty new shared methods cover actual owner/version output, absence, unresolved-only no-op,
semantic replay, malformed receipts, uncertain replies, bounded contention, denied/cancelled
confirmation, changed source, alternating source/index absence and an index removed before the commit reply. All22 prior publisher,
16 Group storage and8 Company storage methods remain. The storage fixtures also check source
and destination versions on every successful old publication path. Focused68/full17010 preserve
all16970 prior identities plus40 desktop/browser executions. Apple source/test compilation is
separate from actual new Apple execution, which remains outstanding.

Three rejected focused attempts remain recorded: the first did not execute tests because two
unrelated inbox fixture overrides had the wrong proposal type. After restoring their existing
contract,30 tests ran and5 rejected unresolved-only routing through the nonempty activity
projection policy. The publisher now validates/captures index metadata and takes its explicit
checked no-op path when no original resolves. A third static check rejected method length; metadata acquisition and no-op selection now belong
to the existing run orchestration. No new helper, suppression or relaxed rule was added.
The intermediate focused66 passed before two new actual-storage races reproduced false empty-pair
confirmation. They now require both absence conditions in one owned transaction; an expected-null
index replacement cannot remove any existing generation. All original test identities and
assertions remain.

The existing Group repair owner still needs atomic cursor/unresolved handoff, destination-generation
replacement handling, durable completed demand and fair finite continuation. No new worker, timer,
per-room observer, UI, native graph, phone release or live server activation is introduced here.
These HRIS-specific receipt contracts remain downstream of the independently ported generic store.

Evidence: `docs/measurements/collaboration-history-inbox-receipts-2026-11-14.json`.
