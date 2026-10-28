# Durable command storage

`AppDatabase.commands` retains encrypted requests independently of disposable
replicas. Logout and a replacement session revoke access without deleting an
unconfirmed operation. Identity persistence binds the active session to a stable,
length-delimited API-audience/account key. A later authenticated session for that
same account can recover its records; another account cannot read or update them.
This local binding does not grant server permission or offline company admission.

The raw store performs database I/O only. Features encrypt request and outcome
envelopes, map storage results, and own command eligibility, retry, authorization,
conflict review and retention. No HTTP operation starts from a storage callback.
Feature integration is separate from this storage capability.

Content-key creation can use the [atomic credential port](protected-credentials.md).
The raw database does not create or recover cryptographic keys itself.

Each partition retains one operation per resource. The operation ID, resource,
ordering key and request ciphertext are immutable. Updates change only the outcome
envelope and compare its observed revision atomically. Removal also requires that
revision. Repeating an identical insertion recovers the existing operation without
overwriting a later outcome. An operation cannot silently replace another request.

Every scoped query and write checks the current session/account in the same
transaction. Pages contain at most 50 records. Storage is limited to 128 records per
account and 512 per database, with 128 KiB request and 32 KiB outcome envelopes.
Cancellation leaves an absent or complete transaction. A committed browser write
still notifies observers if its caller is cancelled. Owners close database resources.

`observeAll` observes the complete selected partition, bounded by the existing
128-command account capacity. It supports summaries that must include retained
operations beyond the ordinary 50-record page. It preserves account checks,
ordering, logout invalidation and cancellation; it does not schedule delivery or
classify outcomes. SQLite and IndexedDB share coverage through full capacity,
outcome changes, removal and replacement login.

SQLite and IndexedDB contract tests cover competing writers, lost local responses,
immutable requests, account/company isolation, logout/re-login, bounded capacity,
cancellation, migration and reopening. Encryption, backend receipts, UI actions,
and physical-device behavior require their own integration checks.

Validation: the Linux workspace check, Android dev debug build, and actual SQLite
and IndexedDB tests passed. This foundation is also ported to Compose Fluent Starter.
