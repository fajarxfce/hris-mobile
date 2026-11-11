# Original incoming storage preserves Company metadata and history sources

An unpublished original chat needs its actual Company, conversation and physical room before the
incoming worker can read provider history. A published Space conversation binding cannot represent
that original safely. A dedicated original metadata repository now retains complete historical
conversation and room facts under the Group account cache, with Company-qualified lookup keys.

The repository reads by Company/conversation or Company/server/room. Both HMAC indexes and their
encrypted payloads authenticate audience, account, Space, purpose, generation, document and revision.
Reads authenticate the complete matching pair and recheck its current checkpoint. Retention atomically
creates both indexes; conflicting room/conversation mappings or changed immutable facts cannot replace
an existing record. A lost write response leaves a complete durable pair for the owning use case to
confirm later. Cancellation propagates without replay. No API, SDK, provider, inventory scan or worker
wake is created by metadata retention.

The partition stays under the existing history-key inventory. If encrypted original metadata remains
but its key is missing, the store refuses to generate a replacement key. Same conversation and room
names can coexist in different Company namespaces; another account, audience or Space cannot read
those records. Replacing the login session or selected HR Company does not change historical ownership.
The metadata contains no native parent or credential expiry and grants no content-read permission.

The existing Company traversal repository additionally exposes an exact source read: one event's
pending or resolved reference and its checkpoint from the same indexed database transaction. It
reuses the current Company codec, provider-null convention and original device cursor. Missing
partitions allocate no key or cursor; missing events retain the actual checkpoint without inventing
coverage. A different Company, device, purpose or physical room cannot reuse that event ID. This
read performs no full pending-reference scan or network request. The owning consumer still rechecks
live authority around suspended reads; raw historical facts do not grant current permission.

Domain owns the immutable fact and query policy; the data repository owns finite ReplicaStore access
and the dedicated codec owns encryption and indexing. The application dependency graph resolves one
repository instance. Original provider/content/completion consumers remain pending and must validate
current Group/native authority, exact source and registered original Company/device before reading.
The raw collector remains disabled. Existing Company content/cursors are not copied or relabeled.

Validation accepts 121 focused desktop and 119 browser cases, followed by
11,621 full cases without failures, errors or skips. Eighteen new metadata database cases cover
reopen, namespace isolation, index immutability, malformed input, ciphertext/index tampering, key loss,
interrupted commits, concurrent append contention, account retirement and cancellation. Existing
Company history and published binding contracts and every prior case identity remain. The existing
application DI case additionally verifies the new singleton. Seven additional shared traversal
cases run in both Browse and Incoming on desktop/browser. Two new Apple wrappers prepare the same
Company contracts for hosted Apple validation. Full checks include format, architecture,
detekt, Android host/release compile/lint, browser/Wasm, iOS metadata and proof APK assembly/unit tests.
Source and library fingerprints and retained unchanged dependency closures are verified.

No new native SDK execution, Apple runtime acceptance, phone installation or live deployment is
claimed by this local increment. No generic Starter change is required. Original consumers, complete
native inventory, keys-only recovery and native proof remain before raw activation; all54 PRD groups
remain open. Continue with the owning original incoming path after this storage prerequisite.

[Exact validation evidence](measurements/collaboration-space-original-room-metadata-2026-11-11.json).

Portable hosted control [42988568153](https://github.com/fajarxfce/hris-mobile/actions/runs/42988568153)
on immutable878d5120eaa7307cbf9615e38d62126be9ce74a4 accepts9,599 cases. Digest/ZIP CRC, prior IDs,
original metadata, both Company history purposes and all12 physical-worker cases are verified. Apple
remains pending, expected3,795 plus16 Swift checks and unsigned host; no SDK8 Apple native claim.
