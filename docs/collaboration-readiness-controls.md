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

## Acceptance and remaining integration

Domain/storage and integration checks pass82 desktop cases and20 actual Chrome
cases, zero failures/errors/skips, on unchanged inputs. The full root/Android gate
remains for native integration. [Evidence](measurements/collaboration-readiness-receipt-2026-11-08.json)
retains compilation, dependency-harness and empty-browser-filter failures.
The SDK listener and application collector are not connected yet, so this source
checkpoint changes no product delivery behavior. The native listener must be
installed before sync, fenced and released with its exact client, and feed one
bounded application-owned consumer. Failed, missing or overflowing hints still
need startup/connectivity/interrupted-activation recovery over the same originals.

Backend V116.3/V116.4 and its control worker are not deployed. Live schema116.2 and
FCM readiness remain unchanged. Isolated migration/old-runtime compatibility,
final package staging and actual FCM-independent native proof are required before
enabling the new path. No physical phone or native Apple acceptance is claimed.
