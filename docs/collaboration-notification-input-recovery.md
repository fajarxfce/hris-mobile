# Recovery of protected notification input

The application recovery worker discovers protected device input before ordinary
account replies, priority hints and company synchronization. Native capture calls
remain a separate entry point. The existing Android startup and registered periodic
tasks can discover a retained input even when its separate scheduling request was
interrupted. OS execution timing is not guaranteed.

Discovery retains a finite snapshot of at most64 opaque ID/generation references.
Four bounded metadata reads collect20/20/20/4 references, with strict ordering and
current-parent checks after each read. It never decrypts or returns a reply token or
text. A snapshot is not a transactional view of the changing device queue.

The snapshot belongs to the current account and is encrypted with its existing
history key in a separate replica checkpoint. Discovery never creates a history key.
The envelope authenticates account, audience, partition and generation; schema,
reference shapes, uniqueness and position are validated. Plaintext is bounded to16KiB
and the encoded envelope to32KiB. Existing account ownership and exact checkpoint CAS
protect competing updates; no schema migration or device-input deletion is involved.

Each snapshot has a cycle ID, next position, pending-attempt flag, accumulated retry,
continuation and finished flags. The worker persists its next position before trying
one reference. It then calls the existing serialized `resumeProtected` path and records
the outcome. An interrupted attempt remains a retry even if later entries succeed.
Restart resumes the retained snapshot, so a failing first input cannot keep the worker
on the same first page. Another owner cannot settle an outdated cursor version.

After every captured reference has been attempted, finish rereads bounded current
references. A new ID or generation absent from the snapshot requests another pass,
including arrivals before the old seek position. The snapshot itself remains finite
under continuous arrival. The next cycle reads a fresh snapshot; retained failures
and newly arrived input are not expired or evicted.

Input recovery has a four-second application phase with at most two attempts, each
bounded to one second. It shares the existing reply mutex through transfer, exact
device cleanup, account reread and command processing. Explicit access denials can
settle without a transport retry. Storage, authentication-as-storage, timeout and
other uncertain failures retain retry; the worker does not guess that a crypto error
means another account. A completed snapshot with failures returns an error retry,
while incomplete work or new arrivals request continuation. Neither can acknowledge
the global wake while another recovery phase is pending.

The overall worker remains bounded to90 seconds, with existing reply, priority and
company limits unchanged. Cancellation joins input work before another phase begins.
The virtual-time fairness case checks4+8+20 seconds of independent timeouts followed
by49 seconds of company work, with the wake still retained. This is a scheduling
contract, not a device-throughput or latency measurement.

Validation includes domain transitions and real SQLite/browser cursor storage,
account replacement, response loss, key loss, tampering and cancellation. The actual
application fixture exercises64 protected inputs, a failed prefix,32 container reopens,
later retry, new arrivals, cancellation and old-family review with fictional transport.
The Android provider fixture now supplies its own persistent input credential adapter;
its existing scenarios do not inject notification reply callbacks.

The [Android callback proof](collaboration-notification-callbacks.md) now exercises
native receiver ownership, generic capture feedback and retained input across three
ordinary process deaths. The product host and reviewed-reply UI are now implemented;
see [reviewed replies](collaboration-reviewed-replies.md) and the current handoff.
Actual product FCM delivery remains a separate pending gate, so live chat push stays
disabled. No user-phone interaction or physical iPhone execution is implied here.

## Intermediate Android recovery checkpoints

Transfer and old-family review are separate durable commits. An interrupted finite
worker turn can retain the original protected input, one exact account copy, or both.
That copy may still await review. The native fixture accepts this intermediate state
only during an incomplete Retry; text/token equality and absence of a draft/resolution
remain mandatory. Complete requires exactly one SessionChanged copy and removal of
the original protected input. Loss, duplication and other review reasons fail.

Each turn now records reviewStates alongside protected/account row counts. The host
driver validates their correspondence and rejects missing or pending final review.
Three checkpoint unit cases and 27 driver regressions cover these boundaries.
[Local Android evidence](measurements/chat-input-checkpoint-native-2026-11-03.json)
passes the existing 16 raw cases, four runtime cases, three process deaths and five
native actions with actual SystemUI Reply. All three screenshots were inspected.
This execution completed in one 758ms turn; it does not claim to have observed the
intermediate Pending state. Hosted run41650906105 remains rejected, and a matching
replacement is required. No production worker deadline or policy changed.
