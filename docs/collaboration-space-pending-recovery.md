# Durable pending Space recovery

The registered application worker now discovers queued Space messages even when their
conversation has no local room binding. It retains one original message claim before
preparing the room, staging the intent and delivering through the existing mixed
Company/Space writer. The message ID, creation Instant and physical SDK transaction
survive interruption; completing an attempt never confirms the message.

## Ownership and bounded progress

The worker composes use cases. `ReadCollaborationSpacePendingMessages` reads and validates
the complete local partition. Pure progress policy chooses the next exact Instant/ID,
and `UpdateCollaborationSpaceRecoveryProgress` commits the claim with generation-bound CAS.
`LoadCollaborationSpaceRecoveryMessage` resolves the exact claimed record and canonical
local metadata under current native-family/session/directory admission. It performs no
SDK work, creates no conversation and grants no permission to deliver.

`PrepareCollaborationSpaceRoomBinding`, `StageCollaborationSpaceText` and the existing
`DeliverCollaborationSpaceOriginalMessages` retain their own admission and physical-writer
checks. No use case invokes another use case. Repositories remain responsible for their
own datasource I/O; no worker logic moved into a ViewModel or storage adapter.

An old pending binding claim resumes first. Unfinished message work precedes the remaining
binding scan. Each turn attempts at most four commands or rooms, within the existing90-second
processor deadline. A failed attempt durably advances its original cursor before returning
a scheduler retry, so later messages can receive a turn. Unready recipients now return
`Unavailable` from the owning staging use case; the former `Conflict` incorrectly blocked
recovery. The original message remains queued or uncertain until it can be recovered safely.

Missing or valid terminal records end only the claimed attempt. A cancellation returned
while staging also skips delivery. Malformed terminal records still fail validation. A
successful delivery report must confirm the claimed ID and exclude it from unresolved IDs;
an unrelated confirmation does not complete that message.

## Compatible encrypted progress

The existing `collaboration-history/space-recovery/v1` partition and authenticated scope
remain unchanged. Schema1 remains readable and writable when the optional message phase
is absent. Schema2 adds a bounded cursor, one pending message/conversation claim and explicit
end-of-scan. A null legacy phase means unscanned, never an empty pending partition.

Canonical Instant strings preserve submillisecond order. Strict schema/field checks,
4096-byte plaintext bounds and existing authenticated native-family/Space partitioning
remain. Binding and message claims cannot coexist, and an unfinished claim cannot be
replaced by a later selection. Mutable Staging baselines are detached before subsequent
suspending reads.

Only `FinishCollaborationSpaceRecovery` can finish a wake. It checks current ownership,
the final binding inventory and the complete pending message partition, including legacy
schema1 progress. Newly pending commands restart a finite sweep; newer wake generations
survive completion of an older captured wake. The generic progress updater rejects its
public Finish change instead of bypassing this full-partition check.

## Validation and remaining integration

Focused validation passed251 fresh cases on Desktop and actual Chromium, including25 worker
cases and2 application DI checks. iOS source compilation, formatting, architecture and detekt
also passed. The first candidate's test compilation failure and its corrected source are
retained in the measurement history. Full validation passed10,010 verified cases:7,991 fresh +2,019 exact accepted reused,
zero failures/errors/skips,581.55 seconds. Required root checks, Android host/release compile/
lint, Wasm/iOS source, actual Chromium and proof APK/unit/quality checks passed. All4,732
source/library inputs stayed fixed. [Measurement](measurements/collaboration-space-pending-recovery-2026-11-10.json).

Required next validation invokes the actual registered worker for a new unbound Space
command and interrupts receipt projection, then recovers original transactions after process
loss. The previous mixed native proof invoked delivery directly and is not evidence for this
worker path. Hosted Apple shared checks and an unsigned host do not implement the still-missing
Apple Matrix runtime. Product routing, incoming/history/edits, media, backup, calling and
meetings remain separate; all54 communications acceptance groups remain open.
