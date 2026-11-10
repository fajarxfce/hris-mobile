# Owning Space message staging

StageCollaborationSpaceText turns an already retained Space intent into an exact physical
SDK mapping. It does not insert a new intent, create a room/key/client, resume sending,
write acknowledgements or retire history. Its singleton owns one bounded invocation and
one staging-only queue lease; concurrent callers receive the existing queue-busy result.

Owned terminal records remain readable without current transport credentials. A queued or
uncertain record needs current parent/directory/runtime admission. The usecase validates
canonical room transport and the full paused queue before inspecting Space commands and,
only for a LEGACY room, the original company command/journal. Other unresolved Staging on
the same physical device blocks enqueue. Every captured original belongs to exactly one
ledger; existing Sent originals require exact journal and/or archive evidence. SPACE rooms
never consult a company command partition.

Canonical preparation must match the installed sender and retained attempt. The usecase
writes Staging with the exact device, original content identity and complete transaction
baseline, then reads back the exact next record before enqueue. It inspects the paused
queue after enqueue even when its response failed. Recovery requires the same logical ID,
content origin, device and unique original transaction, with the complete baseline intact.
Changed/extra/missing inventory remains unresolved; neither queue absence nor an error
creates another transaction or means Sent. Exact Staged CAS also requires readback after
success, false or a failed response.

Recipient readiness is distinct from recovering a stored original. An unready queued
intent remains local; an already enqueued Staging original can recover its mapping. The
existing recipient-unavailable Conflict classification prevents treating readiness as a
transient transport failure. Confirmed denials remain sticky, ownership/deadlines are
rechecked around repository I/O, and cancellation retains any committed intent/attempt.
The native child owns close/drain even after its caller disappears.

Nine pure recovery cases and 25 owning-usecase cases cover response loss, failed/false CAS,
partial persistence, cancellation, deadlines, conflicting callers, replacement login,
late denial, readiness, old journal/archive proof, changed origins and new-room isolation.
Raw enqueue in the domain fixture is synthetic. The separate Android capability tests
cover physical writer ownership; neither suite substitutes for actual native enqueue.
[Local acceptance](measurements/collaboration-space-message-staging-2026-11-10.json) records
the checks actually completed, including the retained first quality-check failure.

Mixed delivery must refuse resume while any same-device Staging is unresolved, including
an attempt absent from the SDK queue. Complete pending-command inventory, typed delivery/
callback-loss recovery, native proof and incoming history are required before product
routing. Actual Apple Matrix runtime binding remains required. This increment enables no
new UI route or worker sender.
