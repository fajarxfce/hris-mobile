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

Native receiver lifetime, failed-capture feedback, review UI and notification-specific
OS-death replay remain the next acceptance work. Production notification hosts remain
unbound and chat push stays disabled until those flows are usable. No user-phone
interaction or physical iPhone execution is implied by these checks.
