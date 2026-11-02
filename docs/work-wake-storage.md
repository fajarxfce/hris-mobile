# Durable work wake storage

`AppDatabase.workWakes` stores application-global scheduling metadata separately
from account-owned replicas and commands. Each stable registered task key has one
bounded generation and pending flag. Records contain no account/company identity,
credentials, message body or remote permission. A current worker must independently
capture and authorize the account and resources it processes.

The consuming policy generates fresh, non-reused generation identifiers. `request`
atomically marks that generation pending. Repeating the current generation returns
its existing state, including a completed acknowledgment, so a lost response cannot
reopen that same request. `acknowledge` succeeds only while that exact generation
is still pending. A newer request therefore survives an old worker's completion.

Keys and generations contain at most128 ASCII identifier characters. New-key
capacity is checked within the same write transaction, with an explicit1–128 key
limit. Existing keys can advance when capacity is full; acknowledged keys remain
reserved. A null request result means capacity, not success. Task identifiers must
remain stable; choosing registrations and retirement belongs to the application.

One application database owner owns these stores and closes their connection.
Room12 adds `work_wakes` through the11→12 automatic migration. IndexedDB11 adds its
object store to version10. Existing commands, inbox and account partitions remain
separate. Account replacement does not clear this payload-free scheduling state.

Already-cancelled calls are rejected before database I/O. Once a transaction has
started, cancellation can race a durable commit; callers must inspect retained
generations after uncertainty rather than assume rollback. IndexedDB acknowledges
transaction completion, not an individual request callback.

The consumer must persist a wake before requesting platform execution. This raw
store does not enqueue work, guarantee OS delivery, enumerate companies or receive
FCM. Startup/reconnect/periodic recovery must repair the gap between persistence
and scheduling. Acknowledging a wake does not acknowledge a message. Worker payloads
remain in their own encrypted durable stores and platform jobs carry only task keys.

[Validation](measurements/work-wake-storage-2026-11-02.json) records actual SQLite
and IndexedDB generation races,40 competing capacity requests, same-generation
replay, cancellation, account replacement, close/reopen and previous-schema
migration. It preserves the first failed cancellation case and its fix. All six isolated Apple cases also pass in hosted run41396150119 at be8ebc7b: actual
Room/BundledSQLite generation, capacity, cancellation, ownership and reopen checks.
The complete iOS simulator application and generated associations pass.

HRIS maps this primitive through [durable chat recovery requests](collaboration-recovery-wakes.md).
