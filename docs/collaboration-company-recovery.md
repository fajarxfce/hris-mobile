# Durable company recovery progress

An application-owned recovery sweep retains its position independently of the
selected company and the OS job. `ReadCollaborationRecoveryProgress` captures local
account ownership; `UpdateCollaborationRecoveryProgress` performs an explicit
compare-and-set transition. Neither operation admits network, company content or
native SDK work. Expired transport credentials do not hide owned progress.

The encrypted account partition records a fresh generation and an optional sweep:
the original wake generation, last attempted company, any unfinished attempt and a
sticky retry and healthy-continuation flags. Begin preserves an existing sweep, even when newer global wakes
arrive. Companies advance strictly by UUID; a failure must not starve later IDs.

The single application processing owner saves Attempt before external work and
Complete afterward. A previously unfinished attempt is uncertain, so moving to the
next company preserves its retry requirement. Finish is allowed only after that
owner has reached the end of a validated live device-directory page sequence.
An uncertain attempt, transient failure or successful batch with more durable
company work starts another finite sweep without acknowledgment. Only failures and
uncertain attempts require failure backoff; healthy continuation remains independently
retained across later companies and process restarts. Complete cannot clear either
flag already set by another company. A clean Finish first saves inactive progress, then returns the
original wake for a separate exact-generation acknowledgment. A crash between those
writes repeats a safe full sweep; an older acknowledgment cannot erase a newer wake.
New companies below the current seek require the next full startup/event/periodic
sweep. The live directory is not a multi-page server snapshot.

The repository uses the existing raw ReplicaStore, protected history content key
and ContentCipher. No company or native session is invented for account metadata.
The fixed `collaboration-history/company-recovery` partition remains under the
history-key existence guard. Authenticated data binds audience, account, generation
and format version. Replaceable local/native session IDs are excluded from ciphertext
ownership, while the raw store still fences every operation by the current local
session. Same-account reauthentication can read retained progress. An old account
cannot publish into its replacement, and losing the key cannot silently regenerate
it over retained ciphertext. Metadata and plaintext have explicit size bounds.

The authenticated envelope namespace remains v1. Its encrypted payload writes schema2
and reads schema1 with continuation=false; an old retry or in-flight marker is
preserved. Schema1 cannot claim continuation=true, and future unknown schemas fail
without clearing retained progress. A successful mutation writes a fresh schema2
generation; reading a legacy record alone does not rewrite it.

Null from compare-and-set is a conflict, not success. Use cases guard account identity
before and after I/O and suppress late publication. Cancellation propagates; committed
facts survive cancellation or a lost response. The repository never hides a retry.
The assembled application container registers one repository plus both use cases.

[Validation](measurements/chat-company-recovery-2026-11-02.json) records exact source,
commands and test reports. Twelve domain cases exercise fairness, persistent retry,
account changes, malformed transitions, cancellation and the local-finish/global-ACK
gap. Eight shared storage cases exercise actual SQLite and IndexedDB, including
reopen, reauthentication, competing writes, ABA, cancellation, tampering and key loss.

The [all-company consumer](collaboration-company-worker.md) now coordinates this
progress under one processing owner and a bounded waiter. Incoming room acquisition
and FCM remain separate; no employee APK, backend activation or full communications
acceptance follows from the local progress checks.

The [later continuation correction](measurements/chat-company-continuation-2026-11-02.json)
adds two domain and three shared SQLite/IndexedDB cases for healthy work, failure
priority, persisted flags, schema1 upgrade and future/mislabeled schema rejection.
It also records actual Android worker execution and final full checks.
