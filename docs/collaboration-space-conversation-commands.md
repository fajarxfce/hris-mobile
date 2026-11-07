# Durable organization conversation setup commands

Conversation opening and stable v2 room setup have account-owned durable commands.
An opening retains its peer; a room request retains the exact accepted stable intent
and its immutable participant pair. Both retain operation ID, API/account/space,
original native origin and creation time. Local login handles, bearer credentials,
native session versions and client builds are not immutable business input.

## State and ownership

A command starts Pending. The owning dispatcher must atomically advance it to
Unconfirmed before HTTP. Losing a response or cancelling does not return it to
Pending, replace its ID or prove rejection. Attempts are bounded to 1,000 and storage
revisions stay within JavaScript's exact integer range. Confirmed access/MFA/client
restrictions survive a failed retry. Only a validated setup receipt advances to
Recorded; opening requires version zero, while room receipts must name the original
conversation and a bounded request revision. Recorded results cannot reopen.

The domain entity/contracts and pure transition policy contain no storage or network
code. The repository coordinates raw CommandStore with the existing protected command
key and a strict versioned encryption codec. It does not call another repository or
schedule retry. The codec authenticates account/space/operation, request versus state
purpose and state revision, validates resource/sort metadata and bounds envelopes.
No existing SDK key, physical device ID or message transaction is replaced.

## Retention and recovery

One active command is allowed per open/peer or room/conversation, within the existing
shared capacity of 128 active commands per account. Complete active observation is
bounded; exact known-ID lookup can also return an immutable archived result. Account
or API isolation is checked inside the raw storage transaction. Another local login
for the same account can recover the unchanged original intent; credential age and
selected HR company do not erase owned data.

After the owning workflow idempotently materializes its needed local projection,
`archiveRecorded` copies the exact authenticated state as immutable evidence and
frees its active resource/capacity. It never creates fresh archive ciphertext during
replay. A lost archive response is recovered by exact known-ID lookup. The original
ID cannot become new pending work; different immutable input is rejected, including
when the original is already archived. No archive scanning is introduced.

Independent concurrent encryption of the same new ID can produce a raw immutable
ciphertext conflict. That failed insertion is reconciled by reading the known ID and
validating the winning original intent, not by overwriting it or allocating another
operation. Missing command keys fail closed over active, archived and inactive-account
records; they are never regenerated on top of retained ciphertext.

## Owning integration remains separate

This increment supplies storage and pure policy. It does not dispatch HTTP, materialize
the canonical local conversation projection, activate product routing or grant SDK/
content authority. A setup receipt is not room readiness or a message acknowledgement.
Current admission, finite replay, local projection and explicit server non-execution
evidence before replacement are still required. A null receipt alone can race a delayed
original and is not that evidence. Preserve original pending messages throughout.

## Validation

The focused gate passed 21 cases in 190.699 seconds with 4,369 unchanged source inputs.
An earlier gate failed test compilation because fixture read/raw names collided with
existing history methods; those test methods now have explicit command names. The
failed attempt is retained separately. Desktop SQLite and browser IndexedDB each pass
15 storage cases, and four pure policy cases pass on both runtimes. Real Apple storage
wrappers are included for the existing CI; no physical iPhone test is claimed.

[Final source and results](measurements/collaboration-space-conversation-commands-2026-11-07.json):
full18016 passed 3,598 fresh cases across 447 suites in 813.971 seconds on
4,369 unchanged inputs. Root check, Android host tests, release lint, formatting,
architecture, Detekt, actual browser storage/policy tests and shared Wasm compilation
passed with no failures/errors/skips or Android release lint issues. Reused reports
are counted separately. Cases cover
lost insert/update/archive responses, competing writes, exact archives, shared capacity,
account/server/space changes, login replacement, malformed envelopes, missing keys,
retained denials, cancelled reads and absence of partial publication. No new APK or
phone latency acceptance is part of this storage increment.

## Original first native CI

First native42369792184 for source3b3a5d81 passed on both Android ABIs. Original
artifacts contain543 cases/86 suites each, zero failures/errors/skips, with matching
immutable SDK4 bytes and NDK28.2.13676358. Original queue continuity33.483s x86 and
80.188s ARM preserved keys and transaction IDs through process death, rejected partial
resumption, sent zero provider messages and cleaned up fully. Separate actual HRIS
fixtures retain their own send counts and passed cleanup; non-applicable false flags
remain explicit. No native/provider scenario was repeated to audit this result.
[Original artifacts and hashes](measurements/collaboration-space-conversation-commands-native-ci-2026-11-07.json).
First control42369792173 also completed successfully:2,001 Apple cases/228 suites
and4,869 portable cases/542 suites, zero failures/errors/skips. Actual Apple storage15,
command policy4 and observation7 cases passed; corresponding desktop/browser reports
and application graph/builds passed. [Control evidence](measurements/collaboration-space-conversation-commands-control-ci-2026-11-07.json).
Later metadata/preparation/Open replay retain their own source CI requirement; phone53
latency remains open.
