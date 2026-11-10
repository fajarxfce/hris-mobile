# Offline organization acknowledgement projection

Status: focused and full local application validation accepted. Product and native sending remain inactive.

A retained acknowledgement can outlive the process that was changing its message to
Sent. Reconciliation repairs that local state using the original encrypted command and
journal. It does not call Matrix, renew credentials, select an HR company or send again.
Sent denotes provider acknowledgement; recipient storage and read status require their
own evidence.

The complete bounded journal is validated before any command changes: at most128
originals and512 event aliases. Every receipt has exactly one owned immutable intent,
physical device, transaction and content origin. Only an exact Staged record advances.
Queued, Cancelled or unresolved Staging states cannot acquire an inferred acknowledgement.
An empty journal means no projection work; it does not imply an empty delivery queue.

Already Sent records keep their original primary event. Their matching journal or
immutable completion proves that state. A later alias may omit the primary only when
the exact completion already proves it. Archives participate in the same event ownership
and total alias bounds; conflicting originals reject the whole plan.

`ReconcileCollaborationSpaceMessages` owns repository orchestration and admission. The
application binds one instance to serialize concurrent projection; its15-second total
deadline includes waiting for that instance. External cancellation propagates. Local
session/directory admission is checked around I/O and after suspended admission reads.
Expired transport credentials do not make owned local records disappear. Confirmed
access denial prevents an old invocation from publishing success.

Each write compares the full retained record. A failed or false response can follow a
committed write, so reconciliation reads back the exact original, next revision and Sent
state once. An absent, changed or unreadable result remains unresolved. No automatic
write retry, transaction replacement or compensation deletes the receipt. Process death,
cancellation and partial completion retain work for a subsequent invocation.

Repositories keep raw encrypted I/O; pure domain policies validate proof and build the
projection plan. The usecase performs admission and CAS orchestration. No ViewModel,
UI helper or datasource owns recovery policy. This flow neither archives commands nor
retires journals; those actions require separately admitted history evidence.

Focused validation passes76 fresh cases:14 policy+23 usecase on Desktop and actual
Chromium, plus two app DI cases. Full validation passes9,630 verified results (7,611
fresh+2,019 exact accepted reused),zero failures/errors/skips. Source/library inputs
stayed fixed. [Measured evidence](measurements/collaboration-space-receipt-projection-2026-11-10.json).
Hosted Apple execution remains pending for this source. Native mixed delivery, creation
of new native transactions, complete worker inventory and product routing remain next.
