# Device-owned opaque records

`AppDatabase.deviceRecords` supplies a raw store for bounded inputs that must survive
before an account session is available. It does not grant account access or implement
notification policy. Callers encrypt private content before insertion, authenticate
it before use, keep identifying information out of keys, and own recovery and deletion.
The existing account-owned replica, commands and blobs keep their admission rules.

Each record has a namespace, opaque ID, fresh generation and encoded payload. Keys
are 1–128 ASCII letters, digits, periods, underscores or hyphens. Payloads are
1–131,072 printable non-space ASCII characters. Their format is opaque to this raw
layer; encryption and authentication are not inferred from those syntax checks.
`toString()` on the public value redacts all fields.

`retain` returns the existing namespace/ID record before checking capacity, even if
the candidate has different ciphertext or generation. Otherwise insertion and the
global count check occur in one transaction. A caller chooses a capacity of 1–128
records; a full store returns null and never evicts an input. Global capacity applies
across namespaces. At the maximum the payload bound is 16 MiB, excluding row overhead.
Concurrent connection owners observe committed retained values, rather than each
claiming their own candidate won.

`remove` compares the complete namespace, ID, generation and payload. Callers must
never reuse a generation for a new insertion: stale cleanup then cannot delete a
replacement after the original was removed. No tombstone remains after deletion;
duplicate callbacks and eventual business-command deduplication belong to the caller.
`page` returns 1–32 rows ordered by opaque ID, after an exclusive optional cursor.
It does not promise a snapshot across calls or timestamp-based fairness. A deleted
cursor still works; insertions before a cursor require another scan from the start.

SQLite schema13 adds `device_records` to schema12. IndexedDB schema12 adds the same
store to schema11. Neither migration rewrites existing account data. Account changes
and logout leave device records alone. Database ownership and closing remain with
the platform container. These records are covered by the application's existing
backup exclusions; they are not a cloud-backup format.

Raw storage propagates technical failures and cancellation. An interrupted operation
may have committed; recovery rereads or repeats the same retained identity. IndexedDB
mutations join their transaction completion after it starts. There is no automatic
retry, expiry, background job or notification callback in this component.

The first caller is [encrypted pre-retention notification reply storage](collaboration-notification-input.md).
Its repository and protected device key are implemented separately in HRIS. Authenticated
transfer to an account, callback lifetime, recovery worker and review UI remain
separate implementation work. Production notification hosts remain unbound. Validation is recorded in
`docs/measurements`; do not treat this contract alone as acceptance.
