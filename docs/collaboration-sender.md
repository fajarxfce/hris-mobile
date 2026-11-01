# Admitted text delivery

`DeliverCollaborationMessages` coordinates one finite native room window. The
application container binds one instance; the application/background lifetime must
own invocation. This use case does not create a coroutine scope, retry loop,
transport queue, room, device, SDK store or transaction. UI/scheduler integration
is subsequent work.

Before sending it checks the current live owner, company gate, active platform
device, owned direct conversation and ready room. It acquires the existing
runtime's exclusive paused writer, captures that resource before checking for
late owner changes, and validates the complete original native family/device/
bootstrap/room binding. Existing journal evidence first repairs interrupted local
`Sent` projections. Any unmapped staging attempt, foreign staged device, duplicate
transaction, mismatched owner or unavailable journal keeps delivery disabled.

The sender passes only original durable `Staged`/`Sent` mappings for this exact
writer to `resume`. Queued text is never implicitly staged. Already confirmed
foreign-device facts remain readable but do not authorize that device's work.
Device/conversation/room admission is checked again after local projection and
before enabling the window. The native SDK owns its existing transport retries;
the product lease permits only the selected room for its bounded lifetime.

## Received facts and command projection

Native results retain acknowledgement aliases separately from pending IDs and
the termination reason. Every returned event must map to an original intent and
transaction. Aliases coalesce in observation order, retaining an existing primary
`Sent` event. Conflicting event ownership and unknown transactions remain
unresolved. A drained native queue with an unconfirmed application intent is
reported as unresolved, never silently marked sent.

Received valid acknowledgements go to the [encrypted journal](collaboration-receipts.md)
**before** command state changes. At most 128 observed receipts become batches of
1–32 messages. Capturing already received facts uses the original raw account
fence under a **five-second noncancellable bound**. This small commit phase may
finish after ordinary authority expiry, caller cancellation, or company gate
closure. It cannot enable another send, change ownership, reopen a gate, or
publish private results. A replacement account fails the atomic storage fence.
Failed/late writes retain their uncertainty; no automatic write retry occurs.

After journaling, gate/owner validation and fresh remote admission run before
projection or publication. Received company denial is retained even if the gate
write or subsequent session read fails. Recipient denial remains scoped to that
operation. With late expiry or denial, the encrypted receipt survives while `Sent`
projection waits for permitted local reconciliation. Partial journal commits also
survive: e.g. a 33-message batch that commits 32 can reconcile those 32 offline
without another native delivery. The remaining message keeps its original mapping.

All terminal paths close the child writer under its existing owned cleanup.
Native work already in progress remains under the runtime if a cancelled caller
does not receive its result. A crash/cancellation **before ACK reaches the journal**
still requires history reconciliation. The use case cannot manufacture evidence
to close that gap, and does not claim notification/background-send acceptance.

## Verification

[Sender evidence](measurements/collaboration-sender-2026-11-01.json) records 18 new
common cases: immutable transaction/alias flow, absence versus acknowledgement,
uncertain/foreign/unmapped work, admission/owner changes, expiry and cancellation
after ACK, partial native/storage failures, bounded persistence, conflicting
receipts, primary preservation and company versus recipient denial.

The separately audited [native delivery CI](measurements/collaboration-delivery-2026-11-01.json)
belongs to preceding Mobile `622d2540`: run `41269736098` passes all 17 steps,
115 Android host cases, and actual process-death HRIS exchange `6aee6870` in
22.689 seconds of test-case execution. Its 179 Mobile / 24 Platform source hashes
match the immutable commits. It proves the native primitive, not this new common
sender through production UI.

Next prove the common sender and receipt recovery together through the actual
SDK fixture, then implement incoming canonical history, bounded windows, command
retirement and the Chat routes. No complete PRD group or capacity target is
accepted by these component checks.
