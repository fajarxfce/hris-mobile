# Durable file storage

`AppDatabase.blobs` stores opaque metadata and encrypted blocks independently of
disposable account caches. A `BlobScope` combines the active session with the
stable account/API binding. Every read and write checks that binding in its
transaction. Logout hides retained files; a later admitted session for the same
account can recover them. An unbound session cannot read or alter them.

Creation reserves declared plaintext bytes before import. A file permits at most
100 MiB. Callers choose stricter account limits, within 200 MiB and 20 files; the
database also enforces 500 MiB and 64 files across all accounts. Reservations
include incomplete imports and are released by atomic deletion. Different company
partitions share the account quota. Pages contain at most 50 metadata records.

Blocks cover exactly 64 KiB except the final remainder. Block insertion and the
header's byte count/revision commit together. An expected revision rejects stale
writers. Completion requires every declared byte, freezes an opaque completion
proof and prevents further appends. A separate opaque checkpoint can change after
completion through an expected revision, while the original file and proof stay
immutable. An obsolete removal cannot delete a newer checkpoint. Deletion removes
the header and every block in one transaction. Metadata/proof/state envelopes are
bounded to 16 KiB; each block envelope
is bounded to 128 KiB. No operation loads a file's complete contents.

This is a raw datasource contract. It does not encrypt plaintext, hash content,
interpret completion proofs, request files, retry transport or expire imports.
The consuming data adapter must supply authenticated ciphertext and bind it to
account, partition, file and block offset. Use cases own import lifetime, admission,
recovery and cleanup. Failed cleanup can leave hidden, encrypted staging records;
it must not be reported as successful deletion. `containsRecords` reveals only an
existence bit for protecting retained ciphertext when a storage key is missing.

SQLite uses Room schema 8; IndexedDB uses schema 7. Upgrades preserve existing
durable commands and their active account binding. Both adapters share behavioral
tests for concurrent writes, account replacement, cancellation, reservations,
pagination and file/checkpoint restoration. The store does not interpret delivery
states or start a transfer.
