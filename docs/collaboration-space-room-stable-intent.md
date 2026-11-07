# Stable organization room requests

New room setup writes use `POST /room/requests` and the original operation ID as
`Idempotency-Key`. Group `d37459fd` implements the separate stable v2 namespace:
space, conversation, device/version and the optional observed request account/revision
form the immutable business input. Current native session proof and actual build
remain live admission; they are not guessed or persisted as business identity.

`CollaborationSpaceRoomRequestIntent` retains the original audience/account/space,
native origin parent, conversation, operation and request. Its native origin is
metadata, not a credential or permission to revive that parent. Receipt recovery
uses read-only `POST /room/requests/receipts/read` with current session/client headers,
no idempotency header and no company context. A single authentication replay keeps
the exact original body. Responses are bounded to 4 KiB and every echoed field,
account and receipt conversation/revision must match before publication.

Another current native parent may recover the same account's original request.
Denied or conflicting reads remain failures. Null remains uncertain; a delayed
original may still commit. Neither a receipt nor its absence grants provider room
access, message delivery, replacement authority or a message acknowledgement.
The complete-proof legacy reader remains available for existing v1 operations.

Domain entities and pure validation, API services, raw datasources and repository
mapping retain their existing responsibilities. No ViewModel, UI, new DI lifecycle
or business retry is introduced. Durable commands must retain the stable intent
before transport; owning dispatch and explicit fencing remain separate work.

## Validation

[Source hashes and measured results](measurements/collaboration-space-room-stable-intent-2026-11-07.json):
focused tests passed 36 cases in 180.349 seconds; root check plus Android host tests,
release lint, formatting, architecture and Detekt passed 3,560 fresh cases across
443 suites in 366.186 seconds. All 4,359 inputs remained unchanged, with no failures,
errors, skips or Android lint issues. Reused reports are counted separately.

New tests cover authentication replay after native parent/build replacement, exact
original echo/account ownership, absent then late receipts, denied/conflicting and
oversized responses, invalid inputs before I/O, and cancellation before publication.
No APK was built or installed; phone build 53 latency remains unresolved.

## Original first CI acceptance

The original control42366357406 and native42366357400 runs for source79b41269 passed
and their original artifacts were audited: 1,975 Apple cases / 225 suites, 4,817
portable cases / 536 suites, and 543 cases / 86 suites on each native Android ABI,
with zero failures, errors or skips. Both native artifacts contain the pinned SDK4
library bytes under NDK28.2.13676358. Original queue continuity took31.255s x86 and
77.166s ARM, preserving original keys/IDs through process death with no provider sends
and complete fixture cleanup. Separate native HRIS scenarios retain their own send
counts and passed cleanup. This is not physical-phone latency acceptance.
[Artifact digests and exact results](measurements/collaboration-room-stable-intent-control-native-ci-2026-11-07.json).
