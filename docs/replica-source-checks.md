# Exact source conditions for replica publication

ReplicaMutation.Check compares the complete working checkpoint, including confirmed absence,
inside the existing transaction. It changes neither source revision nor ciphertext. One or two
distinct partitions remain allowed, with at least one actual write; all-check batches are invalid.
Every source and destination condition is checked before either documents or a recovery wake
changes. SQLite and IndexedDB preserve the existing rollback, ownership and cancellation rules.

An incoming-history replay can need inbox repair while its retained history is unchanged. A
separate source read followed by a projection write permits stale publication; advancing source
revision merely to guard it introduces unnecessary writes. This raw condition supports the
existing transaction without a new queue, polling or application policy in the datasource.
Actual admitted history snapshot/publication still requires its owning repository and use case.

Thirteen new shared methods exercise actual SQLite/IndexedDB: destination observation, unchanged
source, absent source and later creation, full/staging checkpoint mismatch, condition ordering,
foreign scope and account replacement, invalid batches, wake conflicts, constraint rollback,
competing writers, cancellation/join, lost replies, archive replay and reopening. Successful
check-plus-commit and check-plus-replacement are covered. All previous raw mutation/archive
methods remain, including exact completed archive replay after its checked source advances.

Focused 44 and full 16,377 pass locally. All 16,351 preceding identities remain with 26 added
JVM/browser executions; Android, Web and Apple source/test compilation and the proof build pass.
Actual execution of the 13 new Apple methods remains separate. The eight generic source/test
files must next be ported to Starter with independent checks. This does not activate a Group
collector, change native transport, deploy a backend or install a phone update.

Evidence: `docs/measurements/replica-source-checks-2026-11-13.json`.
