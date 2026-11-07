# Owned canonical conversation metadata

`CollaborationSpaceConversationMetadata` retains the immutable organization, conversation,
participant pair, creator, creation time and canonical version. It contains no local
login handle, native parent, device or provider room. Reauthentication for the same
account can read the same facts through the new local owner. This metadata grants no
current directory, conversation content, SDK or sending authority.

The repository accepts a validated transport conversation only when its local owner
matches the requested account, API and organization. Domain policy validates canonical
nonzero IDs, ordered participants, ownership, creator, version zero and timestamp
precision. The data adapter maps those facts and performs raw encrypted storage I/O;
owning preparation, admission and command orchestration remain in use cases.

One replica partition holds two encrypted documents per conversation: an exact
conversation lookup and an exact peer lookup. Both documents and their checkpoint
publish in one compare-and-set transaction. A failed competing insert or append cannot
publish half an index or replace the winner. The caller explicitly reconciles conflicts;
the repository starts no retry, network request, worker or SDK operation.

An identical retained conversation preserves every ciphertext and storage revision.
Appending a different conversation preserves earlier documents. Contradictory immutable
facts are rejected; a one-sided or inconsistent pair is never silently repaired.
An interrupted committed write can be read and retained again after reopening the
database without allocating another conversation or changing the original ciphertext.

Exact reads use published checkpoints and bounded indexed transactions. When an initial
lookup finds a document, both complementary indexes must authenticate the same immutable
fact and insertion revision before it is returned. The final ownership/checkpoint check
rejects a late result after account replacement. Reads do not scan the partition or
expose an incomplete staging generation. Missing lookup rows remain absent; no scan is
performed to infer unseen corruption elsewhere in the partition.

## Key custody and bounds

The partition is `collaboration-history/space-conversations/v1/<space>`, inside the
existing history key's complete retained-record inventory, like organization root
metadata. The existing protected 64-byte value supplies separate content and index keys.
No new credential, fake company scope or unguarded key namespace is introduced.
Metadata alone, including for an inactive account, prevents key recreation after loss;
existing history writers observe the same guard. Borrowed key bytes are cleared.

Lookup IDs, sort keys and equality tags use HMAC-SHA256 tokens bound to API, account,
organization and lookup kind. Payload/checkpoint authentication also binds the generation,
record purpose and storage revision. Raw indexes contain no participant or conversation
IDs. A shared key cannot correlate the same lookup across different account/API/space
partitions through these tokens.

Each insertion writes exactly two documents. Plaintext envelopes are bounded to 2 KiB,
encoded envelopes to 4 KiB, lookup pages to at most two documents per index, and storage
revisions to the common JavaScript-safe integer range. There is no full inventory scan
or newly imposed product limit on the number of conversations. This increment adds
exact lookups only; directory/list synchronization and pagination remain separate.

## Validation and continuation

Fourteen shared storage cases exercise actual SQLite and IndexedDB implementations;
an Apple SQLite wrapper supplies the same contract to Apple CI. They cover unchanged
ciphertext, restart/login replacement, competing transactions, lost write responses,
account changes during writes/reads, cancellation, scope isolation, corrupt records,
key loss with metadata-only/inactive records, shared history-key compatibility and
invalid inputs. The assembled application test resolves the actual repository binding.

Focused validation passed 16 cases in 183.384 seconds on 4,380 unchanged inputs,
with zero failures, errors or skips, and formatting, architecture and Detekt passed.
Full validation passed 3,614 fresh cases in 448 suites in 476.855 seconds on the same
unchanged inputs, with zero failures, errors, skips or Android release lint issues.
Root check, Android host tests, quality checks, all 14 actual Browser storage cases
and shared Wasm compilation passed. Apple acceptance for this exact source remains
pending. [Source hashes and results](measurements/collaboration-space-conversation-metadata-2026-11-07.json).
No product caller or APK is added here. Owning setup must materialize these facts
before archiving a Recorded open command. Room request receipts, explicit non-execution
fencing, original SDK delivery/history and product routing remain subsequent work.
The reported physical-phone pending delay remains open.

## First source CI — 7 November 2026

Original1d9493df control42372520573 and native42372520624 completed SUCCESS, and their
original artifacts were downloaded and digest-verified. Apple passed2,028 cases/230
suites; portable passed4,923/546, with zero failures/errors/skips. Actual Apple,
Desktop and Browser each include14 metadata-storage and13 Open-preparation cases.
Each Android ABI passed543/86 with the immutable SDK4 libraries and pinned NDK.
Original queued state/private keys survived interruption and process death in43.694s
x86 and79.229s ARM, with zero provider sends in that synthetic continuity case.
Separate actual HRIS fixtures preserve their measured sends, cleanup and inapplicable
false flags. [Exact runs, artifact hashes and selected reports](measurements/collaboration-space-conversation-preparation-control-native-ci-2026-11-07.json).
This is read-only artifact review, not a provider rerun, later Open-replay/fence proof
or physical-phone latency acceptance.
