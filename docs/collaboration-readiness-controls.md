# Committed recipient readiness

A missing first device key must be resolved by that device's owned activation flow.
An established private account with unavailable keys requires recovery; neither a
sender retry nor a server-created replacement key is a safe substitute. Existing
messages remain durable originals throughout activation and recovery.

The controlled native76 test found another delay after keys were published: FCM
accepted the wake promptly but delivered it about297 seconds later. The original
sent once about2.6 seconds after OS dispatch. This motivates a foreground signal
on the existing Matrix connection. See [native evidence](collaboration-readiness-native-proof.md).

## Protocol and ownership

Platform e558a686 supplies the fixed `dev.fajar.hris.recipient_ready` V2 control,
with exactly nine bounded string fields: schema_version, event_id, company_id,
account_id, native_session_id, device_id, publication_revision, published_at and
expires_at. Revision is a canonical positive signed64-bit integer. Publication
and one-day expiry have bounded canonical millisecond encodings. The company
counter is locked until the original publication transaction commits; timestamps
are not used for deduplication or ordering. A higher revision subsumes every lower
committed publication during a full owned recovery sweep.

The native source owner/device must come from the exact SDK client, independently
of packet contents. The domain flow checks the current local session, audience,
account and native parent, then compares company and device against that source.
Ordinary access-token age does not discard owned work. The hint grants no content
or send permission; existing delivery use cases still obtain current admission.

## Durable receipt and scheduling

`QueueCollaborationReadinessSignal` retains one encrypted receipt per account,
native parent, company and device. Its pending revision is persisted before the
existing global recovery wake is requested. The requested revision advances only
after that wake is retained and scheduling succeeds. A delayed completion cannot
acknowledge a newer pending revision; failed or cancelled scheduling leaves pending
work intact. Replayed or reordered fully handled revisions request no additional
wake. Each compare-and-set operation has at most three attempts and the receiver
has a three-second deadline; OS scheduling has its existing1.5-second bound.

The repository coordinates raw replica storage and the existing protected content
key. Associated data binds the account/audience/native parent/company/device and
exact generation. A same-family local-session renewal can reopen the receipt; a
ciphertext transplant cannot. Revisions are encoded as decimal strings so browser
storage preserves values beyond2^53 and through Long.MAX_VALUE. The key is never
regenerated over retained facts, including inactive accounts. There is no unbounded
event-ID ledger, original-message rewrite or transport polling loop.

Scheduling orchestration belongs in the owning use cases. The former suspending
`CollaborationRecoveryWakePolicy` helper was removed; request, resume, activation
and push flows retain their own existing timeout and cancellation boundary.
`ActivateCollaborationDevice.ready` already persists a wake after publication;
the application must not add another readiness wake on every state emission.

## Native subscription lifetime

The Android runtime installs the existing SDK5 custom-to-device listener before
restore/sync. Its raw datasource only registers that listener. The exact client
lease owns the returned handle, fences obsolete callbacks immediately, and joins
pending acquisition before cancel/close and private-directory release. Failure to
cancel still closes the handle and permits remaining cleanup. A close during
subscription disposes the eventual handle without publishing another runtime.

The adapter accepts only the fixed clear control type, the client's own sender,
exact scope and bounded nine-string content. It offers opaque signals to a bounded
32-item stream. One application-owned consumer subscribes before activation and
serially invokes the domain receipt/scheduling flow; it never opens a second SDK.
Cancellation joins the collector. Successful retained scheduling emits only the
closed `ChatReadinessScheduled` diagnostic, without account, device or event data.
Duplicates do not emit that success or start another sweep. Callback failures do
not invent a timer. Existing activation already requests recovery at READY.

## Acceptance and remaining work

The final root gate passes 5,911 fresh cases, including 47 concrete Chrome cases,
with zero failures, errors or skips and 4,517 unchanged application inputs. The 82
inspector inputs are unchanged. Android release/proof compilation, Android lint,
shared Wasm/iOS Arm64 source compilation, formatting, architecture and detekt pass.
No Apple executable or device test was run. The focused 76-case ownership gate
preceded the scheduling diagnostic; the final root gate covers its added test.
[Listener evidence](measurements/collaboration-readiness-listener-2026-11-08.json)
and [earlier receipt evidence](measurements/collaboration-readiness-receipt-2026-11-08.json)
retain the exact scope and earlier rejected harness results.

Actual foreground delivery is still unproven for this listener. Next package APK77,
preserve the existing sender76 store during upgrade, and exercise a new fictional
recipient while the sender's push registration is disabled. Observe committed keys,
Matrix control, retained scheduling and exactly one original encrypted server event
without restarting the sender or resending a message. Preserve the earlier failed
140-second FCM gate and its later successful original; never replay that original.

Platform V116.3/V116.4 and its control worker remain undeployed at this checkpoint.
Both API and worker must change because the old API drops the new control shape.
Validate the isolated additive migration and old/new image compatibility, then
stage development with controls disabled. Controlled enablement for the native
proof follows retained-identity qualification; it is not general delivery acceptance.
Missed/overflowing signals, actual reconnects and interrupted first activation need
continued recovery work over the same originals. Phone and native Apple delivery,
cloud recovery, and the broader communications PRD remain separate open work.
