# Durable room-key consumer design — not activated

The targeted-history prerequisite is accepted locally. The following consumer remains a private prototype.
These notes refine space-room-key-binding/next-key-intent-design.md; no next preparer exists. A preliminary12-file private draft now exists, UNREVIEWED/UNCOMPILED, with18policy,26consumer and8storage cases plus one DI ownership assertion. No application activation is implemented.

## Exact receipt and ownership

One credential-free immutable receipt in the current root's existing incoming-demand snapshot is
sufficient only with one @Single RetainCollaborationSpaceRoomKeys collector holding a collection
Mutex for its whole flow. The application must cancel AND join the previous runtime collector.
A second collector waits before admission, registration or observation. No public retain(batch)
entry point; policies are pure, the serial use case is the only receipt producer. Existing other
writers must preserve the field. Prove this with policy/transaction tests and actual DI identity.

Receipt fields: source(storeId,matrixUserId,matrixDeviceId), logical runtime deviceId/operationId,
and opaque raw receiptId. Partition supplies account/root. Avoid serializing parent credentials or
room-key material. The raw mailbox freezes a delivered occurrence; its opaque UUID plus runtime/source
is exact identity. A newer same-room occurrence has a new UUID and must request a later pass.
Raw receiptId can also be the unique history request generation; replay never creates another UUID.
Reject an already-used generation without matching receipt evidence. Match full provenance, not only UUID.

Store receipt, planned recovery intent and independent wake in ONE existing encrypted transaction.
A lost-write response is confirmed by exact receipt readback, even if worker progress or unrelated
requests have replaced recovery/current/requested generations. Do not use those mutable generations
or wrapper generation as the only witness. Explicit CAS loss is not a successful receipt. Non-storage
failures preserve classification. Retained valid matching receipt on replay allows ACK without another
write/scan/wake. Worker completion must leave the receipt. Never keep an unbounded receipt log or a
finite recent-ID cache as alleged proof of arbitrarily old concurrent writes.

Add an explicit next encrypted schema (likely9) that requires a nonnull receipt and history state;
old1–8 keep semantics. Current target predicates have a24,000-byte budget each, leaving header space
for this bounded ASCII identity. Exercise max129parts, malformed identity, version mismatch, reopen,
reauthentication, lost replies, and preservation by sync/inventory/primary/history writers.

## Admission and local classification

Inside the serial collector, validate runtime, current account/root, local directory admission,
registered original source/platform and exact installed sync source before registration and after
all suspended repository operations. Existing completion/reader admission patterns provide the checks;
no usecase-to-usecase or repository-to-repository calls. Explicitly start the installed listener once,
then observe it. Native registration is cached per owned parent; reads never start another client.
RestoreSession already creates OlmMachine before asynchronous E2EE network/backup initialization.
A null stream is unavailable; do not await broad setup or loop registration.

A reconciliation flag immediately selects account recovery. Otherwise classify <=64 copied rooms
through existing Original.findByRoom(access,registeredCompany,server,room) and Published.findByRoom
(access,server,room). Verify returned immutable metadata against exact requested room/server/company/
Space. Any genuinely unknown mapping requests account fallback; a failed/invalid/denied lookup stays
a failure and keeps the raw receipt. Known mappings select canonical targeted recovery. No API
transport resolver, extra SDK, provider inventory or key backup query belongs in callback processing.
Actual history acquisition is serviced by the existing bounded worker after durable retention.

## Bounded processing and lifetime

For a received batch, capture before suspension and preserve that identity throughout bounded local
CAS/storage retries. Suggested bound: at most3 attempts (5s local operation deadlines; short finite
backoff), only Storage/Timeout and an explicit write-contention outcome. Authorization, validation,
source/runtime change and unavailable native observation terminate immediately. Do not restart the
whole observer from an emission or loop on lifecycle start. After attempts exhaust, emit the failure
and end this owner; a later real lifecycle entry can inspect the retained mailbox. The parent retains
its raw pending receipt until exact ACK or parent disposal. Tests must establish the retry triggers,
terminal failure, cancellation while sleeping/writing/acknowledging, and no hot loop.

ACK only after durable confirmation and a final current admission. AcknowledgeKeys is synchronous
inside the repository after its exact registry check; false cannot consume a successor. No uncertain
ACK result should be interpreted as storage failure or create another request. Schedule the existing
worker as a bounded advisory action; its durable wake already guarantees later service. Preserve
receipt on cancellation after commit and before ACK. Another same-room callback arriving during the
write remains a distinct pending mailbox occurrence and will retain its own requested pass.

Before activation, wire one application collector to actual installed runtime changes, join on
retirement/root/account changes, and prove no SDK acquisition while background creation is idle.
Inspect existing ApplicationCollaborationLifetime and foreground-startup ownership rather than
inventing an Activity/VM owner. Sync/source/restore producers still need coherent activation and
metadata-before-demand causality. Do not enable only keys while leaving required metadata producers
unserviced. Keep worker90s inventory/history/primary fairness. Apple native adapter remains separate.

## ACK result review

AcknowledgeKeys(false) consumes nothing. After exact durable receipt confirmation and current
admission, the owner can finish processing that occurrence without claiming a new ACK happened;
never mint another request or ACK a successor solely because the old receipt was already released.
Test cancellation/timeout after the synchronous raw ACK and before its result is observed. If this
interpretation cannot be proved against the actual adapter, revise it before activation. A failed
AppResult still preserves classification and current admission. Retry explicit contention only when
BOTH FailureKind.Conflict and the operation's contention code match; authority errors never retry.
