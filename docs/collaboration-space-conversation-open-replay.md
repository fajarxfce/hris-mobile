# Owned original conversation opening

Local validation is complete. No product caller or phone update is included;
first Apple/native CI for this source remains required.

`ReplayCollaborationSpaceConversationOpen` performs one explicit, bounded attempt
for an owned original Open command. It never allocates another operation ID or peer
intent. Current native admission is separate from the command's original provenance;
a new native parent can recover the same account's stable server operation.

The use case coordinates command, conversation, metadata, directory and session
repositories. Pure policy validates transitions and separates an unavailable peer
from a restriction applying to the account's whole directory. Repositories retain
their datasource and error-mapping responsibilities; DI stays in the data module.

## Dispatch and response loss

Before HTTP, the owning flow durably changes the original command to Unconfirmed,
incrementing its attempt and revision. A failed, cancelled or unknown mark never
dispatches in that invocation. A successful mark is read back exactly; the original
intent, revision, attempts, phase and stored failure kind/code/retry delay must match.
Changed or missing marks cannot dispatch. Local ownership and finite current native
admission are checked again after the read.

Reading the stored mark matters because directory and command codecs intentionally
normalize private error presentation differently. Constructing the next expected
record solely in memory made a real encrypted-store CAS reject an otherwise accepted
server receipt. The regression test reproduces this before the fix. Dispatch now uses
the actual normalized record for the response CAS; raw CAS remains exact and private
server error text is not persisted as identity.

An observed response receives at most two seconds of persistence independent of
caller cancellation, under the captured local account owner. Cancellation still
propagates. Actual global denials close directory admission; peer unavailability
does not close every contact. Stronger retained denial survives transient failures,
lost persistence and weaker peer failures. A positive setup receipt does not reopen
a denied directory or imply a message acknowledgement.

After a lost positive-result write response, one exact read can recover the recorded
receipt. It does not send a second Open request. Unknown results remain Unconfirmed
under the original ID for a later explicit attempt. Attempts and revisions are bounded.

## Canonical publication and archival

A recorded command first checks account-owned canonical metadata. A matching local
fact allows exact archival without fresh native credentials or another HTTP request,
including after process restart, local login replacement or native-parent rotation.
Ordinary age and selected HR company do not erase these immutable owned facts.

If metadata is absent, current native admission precedes an exact conversation read.
The canonical ID, immutable pair, creator, timestamp and version are validated before
publication. An exact local read verifies publication even if its write response was
lost. Only then may the original Recorded command be archived. Failed publication
or denied acquisition cannot downgrade the recorded result into another Open attempt.
A lost archive response can recover the same retained ciphertext on the next invocation.

Every suspended boundary checks local ownership. Network preparation uses a single
15-second bound, the first native expiry, monotonic elapsed time and wall-clock rollback
checks. Capturing an already observed response has its separately bounded lifetime.

## Validation and remaining integration

Twenty-five domain cases cover ordering, stable original identity, cancellation,
current admission, changed marks, concurrent attempts, local ownership changes,
denial strength, exact receipts, lost writes and finite timeouts. Three shared real
storage cases exercise SQLite/IndexedDB-compatible contracts across command, directory
and metadata codecs, separate retained keys, lost publication/archive replies, and
restart under a replacement login with expired native credentials. Actual application
binding resolves the use case. Apple wrappers share the same storage contract.

Initial fake-domain/binding checks passed 30 cases. Adding real storage reproduced
one normalization failure among three cases. After the fix, focused validation passed
34 fresh cases in 134.702 seconds on 4,393 unchanged inputs, with zero failures,
errors or skips; format, architecture and Detekt passed. The original failed run is
retained. Full validation passed 3,669 fresh cases across 452 suites in 505.461 seconds
on the same 4,393 unchanged inputs, with zero failures, errors, skips or Android lint
issues. Root check, Android host tests, all 25 actual Browser domain cases, all three
actual Browser storage cases, shared Wasm compilation and quality checks passed.
[Source hashes and all runs](measurements/collaboration-space-conversation-open-replay-2026-11-07.json).
First Apple/native CI for this source remains required.

Room request projection/fencing, original SDK delivery/history, provisional offline
peer mapping and product routing remain separate work. Setup receipts and canonical
metadata grant no SDK or content authority. This change does not establish the cause
or resolution of the reported 20-second to two-minute delay on installed phone build 53.

## Original first control CI

First control42376025279 for source31e618cc89e2ca0cce38bcb62ccd95b8533c88c0 is SUCCESS.
Original Apple artifact13577353748 contains2,056 cases/232 suites; portable13577059834
contains4,979 cases/550 suites, zero failures/errors/skips. Their GitHub digests and
actual25 Open replay plus3 real-storage cases on each Apple/Desktop/Browser target
are verified. No provider scenario was rerun. [Artifacts and report hashes](measurements/collaboration-space-conversation-open-control-ci-2026-11-07.json).
First native42376025306 is now fully audited SUCCESS:543 cases/86 suites per ABI,
zero failures/errors/skips, exact SDK4 native hashes and NDK28.2.13676358. Original
queue continuity20.779s x86/74.454s ARM preserved keys/IDs through process death and
rejected partial resume, with zero provider sends and full cleanup. Separate six
HRIS scenarios retain exact send counts and their own accepted cleanup; inapplicable
false flags remain explicit. [Native artifacts](measurements/collaboration-space-conversation-open-native-ci-2026-11-07.json).
No native/provider scenario was rerun to audit these original artifacts.
This source predates the fence transport/storage/owning-resolution slices; it does
not establish their source CI or phone53 latency acceptance.
