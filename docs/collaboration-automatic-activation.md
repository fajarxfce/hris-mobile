# Automatic native chat activation

Implementation candidate under validation; automatic activation is not accepted for
release yet. The earlier runtime-retention and private-account guard gates remain separate. An unavailable recipient must
not cause timed message retries, and this enrollment workflow must not create a
second polling loop.

## Ownership and entry

After native sign-in and an admitted company selection, the application lifetime
starts one cancellable activation for that exact owner. The domain workflow must
capture the selected workspace itself, fence every result against current session,
company, device, build and retained authorization denials, and stop on owner change.
Android/iOS use the protected SDK store. Unsupported web enrollment does no work.
Entering a chat room is not a prerequisite for registration or public-key upload.

The current ChatSetupViewModel orchestrates preparation, replay, receipt
reconciliation, installation and six timed status reads. Move that business
workflow into one named domain use case that coordinates the repositories. Do not
copy it into the application collector or disguise the existing use cases as helper
objects. Replace the manual screen's orchestration with the same domain operation;
keep typed UI events, immutable state and route effects in presentation.

Existing Prepare/Replay/Reconcile/Install contracts contain important crash and
authorization behavior. Consolidation must preserve their meaningful tests, not
discard the tests because names changed. A finite domain state machine may share
pure action, receipt and gateway policies; no use case calls another use case.
The separate restore-only entry remains explicitly unable to enroll/reset keys.

## Required decisions

| Current fact | Automatic result |
| --- | --- |
| No native device and no retained command | Retain one registration operation before sending it. |
| Pending/unconfirmed command | Resume that exact operation, preserving confirmed denials and build compatibility. |
| Registration/retry receipt | Retire only after scoped readback proves it; never infer success from a timeout. |
| Provisioning in progress | Bounded readback with a finite deadline, then retain waiting; no permanent retry timer. |
| Provisioning failed/cancelled | Respect retry-not-before and a bounded explicit policy; never issue unlimited new operations. |
| Active device, restorable SDK store | Publish the original protected runtime, then request the existing bounded recovery wake. |
| Active device, no gateway, no old private identity | Retain the first bootstrap, install only its confirmed outcome, then publish. |
| Active device with gateway but unrestorable private store | Require key recovery; do not silently create replacement private keys. |
| Retained confirmed bootstrap | Own the returned lease before checking late results; retire the exact command before publication. |
| Revoked device, access denial, scope/build mismatch | Stop, retain the correct failure and close untransferred resources. |

Installation alone does not run Matrix sync/key upload. A successful installation
or restoration must request the existing recovery mechanism, which rechecks live
authority before opening transport. Registration and publication are not equivalent:
only a confirmed eligible public-key publication makes a recipient ready.

## Resuming a waiting sender

The sender retains its existing command and SDK transaction identity. A scoped
recipient-device change wakes reconciliation; it is not permission to send. The
worker must reread current HRIS transport and the final gateway checks authority.

The Rust SDK exposes a device-change stream but current FFI does not. An application-
owned listener should coalesce relevant changes, close with its runtime, and cover
late callbacks and missed hints on restart. Do not wake on every sync response or
parse network-inspector events to drive application business behavior.

Server key-publication transactions already append collaboration.device_keys_published.
The push queue already accepts an opaque event-null background hint. Reuse those
contracts only with a bounded, idempotent fanout that admits each current recipient
binding and respects company/account RLS. Do not query another account under the
publisher's scope or fabricate a Matrix message event. Foreground device events and
startup reconciliation must cover missing/denied notification delivery.

Treat provider device changes as hints, distinct from the application's confirmed
publication. A Matrix device-list update may reach the sender before the HRIS
publication transaction commits. If that sender observes recipientReady=false,
consuming the provider hint cannot leave the original asleep forever. Test this
ordering explicitly and deliver a durable, scoped hint after the HRIS commit;
coalesce duplicates without acknowledging a newer readiness generation.

The existing recovery scheduler also needs an activation-path test. Collapsed
WorkManager requests can already be waiting in backoff, while the current foreground
scheduler accepts only the delivery task. A newly committed activation/readiness wake
must reach an eligible foreground recovery owner promptly, without resetting unrelated
business retry policies or relying on the user reopening a chat. Prove foreground,
background, killed-process and denied-notification paths separately. Do not infer
prompt activation from successful WorkManager enqueue alone.

The separate passive-wake fix preserves ordinary application recovery while removing
delivery requests from repeated admission renewal and timeline acquisition. Activation
and readiness must have their own explicit completion/event triggers; they must not
restore those accidental retry sources. A periodic safety sweep can recover missed
hints, but cannot be the normal delivery path for a newly ready recipient.

## Acceptance still required

Prove a fresh owned recipient can sign in, select its company, and publish keys
without opening setup/chat. While its original sender is waiting, activation must
resume that same original once, with no replacement body/transaction and no timed
transport loop. Preserve both device stores, synthetic originals and retained drafts.
Test cancellation/late responses at every side effect, lost replies, retained denial,
failed retirement, runtime disposal, repeated owner emissions, delayed provisioning,
key-store loss and readiness events racing with revocation or owner changes.

Any server migration requires a reviewed isolated rollout relative to live116.1;
pending group migrations117–138 must not be deployed as a side effect. SDK FFI changes
remain unpublished until portable and real Apple CI acceptance. This document is
not evidence that automatic activation, readiness wake, iOS runtime or calls/meetings
have shipped.

## Private account preservation before automation

The legacy runtime previously constructed the native SDK using an initialized protected
credential record without first reading the existing crypto account. Matrix session
restoration can initialize an account when the crypto store is empty. Automatic entry
must inspect inventory/possession first and the runtime must revalidate the original
identity under the directory lease before constructing a client or replacing credentials.
The current side candidate/tests are not accepted yet. A credential replacement resets
`initialized` while retaining old message lineage: that interrupted state must also
require the existing private account. A new gateway generation without original private
state cannot authorize replacement identity creation. Only a proven initial bootstrap
may initialize first keys; uncertain old state stays recoverable and untouched.

Use the existing inventory/possession repositories for the domain decision. Missing
facts, technical read failures, an in-progress handover and verified possession are
separate outcomes. A read-only inventory check cannot replace validation under the
actual SDK directory lease. Restore-only callers remain unable to enroll/reset keys.

## Candidate implementation and remaining execution work

`ActivateCollaborationDevice` now owns registration/bootstrap preparation, durable
replay, receipt retirement, private-account inspection, runtime installation/restoration
and recovery-wake persistence. The old four command use cases are consolidated into
named internal stages; their existing crash/denial/lease tests now exercise those stages.
`EnsureCollaborationRuntime` remains an independent restore-only operation for its other
callers. Platform capability defaults false, and the Android implementation enables it.
Unsupported hosts do not enroll through this workflow; this is not an iOS runtime claim.

The domain workflow allows six delayed readbacks and sixteen total transitions.
Automatic entry never creates a provisioning retry; explicit continuation may retry
once after the server deadline. An uncertain command resumes its exact original ID.
A stale Resume with no retained command only inspects, without inventing registration.
A genuine first bootstrap differs from established private-account loss. Missing,
foreign or unreadable original keys cannot authorize a replacement private identity.
The runtime still rechecks private identity under its actual directory lease.

Application ownership starts one cancellable run on admitted selection, cancels it on
owner loss, and keeps ordinary age/renewal from starting another run. Observers of an
active automatic run consume shared, scoped domain progress instead of another poller.
Closing a setup page cancels only that observer. The initiating application owns the
run, and durable commands remain authoritative after process loss. Local runtime Ready
is distinct from confirmed server public-key publication. A persisted recovery wake
survives OS scheduling failure; failed enqueue is retained separately from local readiness.

[Focused validation](measurements/collaboration-device-activation-2026-11-08.json)
now passes94 tests across domain, UI, application ownership and assembled bindings.
The final gate passed with19 fresh UI tests and75 unchanged, previously passing tests;
all4,500 application and82 library inputs stayed frozen. The first attempt found a UI
assertion type-inference error; the next found only its formatting. Both are corrected.
Full repository checks now pass5,904 fresh tests with zero failures/errors/skips in
1,002.886seconds. All4,503 application/config and82 library inputs stayed unchanged;
root, Android, browser, sharedWasm/iOS source and quality gates passed. Native acceptance
of activation remains pending. The separate
private-account guard already passed its5,793-test full gate on the primary branch.

Prompt recovery execution now has a focused-tested candidate. AndroidWorkScheduler emits
its host callback after feature intent persistence and before the potentially slow OS
queue inspection. The [request-driven foreground runner](collaboration-requested-recovery.md)
uses the existing conflated lane while WorkManager owns durable retries. Recovery does
not inherit the delivery runner's one-second retry policy or another periodic timer.
The focused gate covers40 portable worker cases and34 application recovery cases,
including retained requests, in-flight signals, lane isolation, cancellation, exact
wake generations and simultaneous OS/foreground execution. Full acceptance passed;
native acceptance remains pending before the reusable runner is ported to Starter.

Completion of activation must then reach that lane. Test fresh key publication without
opening chat, original sender resumption, foreground/background/process restart and
notification denial. Readiness still needs a scoped post-commit hint and missed-hint
reconciliation as described above. Automatic recovery after connectivity returns during
an interrupted first setup also remains to be integrated; repeated admission refresh is
not an acceptable substitute trigger. Existing key-recovery UX currently reports the
need for recovery; cloud backup/import itself is not implemented by this change.

## First native activation and cold-start repair

Release75 created one active device and confirmed its first public keys8.455seconds
following ordinary email sign-in and company selection on a fresh owned emulator.
No setup/chat page or send gesture was used. Its first cold restart then captured
an actual private-store ownership collision. The diagnostic root restart interrupted
the UI driver; later Home restoration with the same one device is continuity evidence,
not acceptance of the failed startup. [Native75 evidence](measurements/collaboration-automatic-activation-native75-2026-11-08.json)
remains failed. Both that original image and the older Bima image are preserved.

[Runtime inspection coordination](collaboration-runtime-inspection.md) addresses the
race between activation's inventory/private-key reads and background SDK creation.
The proposed resource reservation covers the reads without moving authorization or
key-loss policy into storage. Its regression/full/native76 checks are ongoing. Phone
remains70;75 was never installed. Server readiness wake and interrupted-connectivity
recovery remain required after this repair, along with the broader collaboration PRD.
