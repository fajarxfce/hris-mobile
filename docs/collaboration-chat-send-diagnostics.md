# Pending-send diagnostics

Android acquisition emits closed stage/category markers for room, encryption,
timeline and queue creation. Retained timeline failures and new send-queue errors
use the structured SDK error variant; private device/user fields are never read.
Generic Rust details are used only for bounded, exact known variant prefixes. No
provider detail, message text, identifier or token is logged.

The application delivery owner records its last stage when a result fails or the
room budget expires. Recovery lanes record only their fixed name and FailureKind.
Successful work and ordinary cancellation stay quiet. Exceptions, cancellation,
admission, encryption trust and retry behavior are unchanged.

Tests exercise private error fields, exact/bounded prefix matching, cancellation,
original exception identity, failure stages, timeouts and quiet success. Root,
Android compile/host, browser data/presentation, proof assembly, formatting and
architecture pass: 2,155 fresh cases, no failures/errors/skips, 417.551 seconds,
4,066 unchanged source inputs. Focused 30 cases pass. An earlier combined attempt
failed the proof fixture long-method rule; extraction resolved it before the final
successful gate. [Evidence](measurements/chat-send-diagnostics-2026-11-05.json).

Both old and new Bima sends still fail on installed release32. Successful gateway
refreshes and history/sync/join responses do not identify the queue failure.
Optional secret-storage reads returning403 are not a demonstrated cause. Do not
reset the device, keys or pending intents, relax encryption trust, or blindly
release an unrecoverably wedged queue. Deliver the validated diagnostic build and
use the closed markers to identify the failing operation.
