# Recovery request ownership

An invocation is not a new recovery intent. Android sends one persisted continuation
to both a foreground consumer and WorkManager. Either may finish it before the other
starts. The delayed consumer must see that the wake was acknowledged and stop.

The previous `CollaborationRecoveryWorker` called `EnsureCollaborationRecoveryWake`
on every invocation. After a foreground completion, a delayed OS invocation could
create another wake. Its bounded company/room continuation then scheduled both hosts
again. Per-pass limits did not bound this chain of newly manufactured sweeps.

The regression first failed on unchanged production source: two companies were
covered, then three delayed requests increased remote directory reads from two to
five. The retained Android77 capture also recorded 1,500 requests in 215.864 seconds
for one company with two conversations. Most were setup/conversation/room metadata;
only four optional default-key requests occurred, all404. The native repetitions
eventually settled; the capture does not establish literally infinite execution.
See [native evidence](measurements/collaboration-readiness-control-native77-2026-11-09.json).

## Trigger and durable intent

The generic domain `SyncTrigger` distinguishes `Requested` execution from `Scheduled`
discovery. Existing tasks retain their original behavior through the default overload.
The generic host communicates the trigger; the feature decides whether it may create
an intent. No worker infrastructure knows about chat, account ownership or keys.

`CollaborationRecoveryWorker` creates/retains a wake only for scheduled discovery.
Requested invocations read the existing wake. If absent, they return before input,
reply, priority, device, company, history or delivery work. Existing parent guards,
serialized processing, account cancellation and generation acknowledgments remain.
Failed/cancelled work retains its original wake; a genuinely newer request still
wins over acknowledgment and gets its own finite sweep.

Android one-time jobs dispatch `Requested`. Periodic jobs carry a fixed infrastructure
tag and dispatch `Scheduled`. Updating installed periodic jobs adds the tag while
preserving WorkManager identity and cadence; no old jobs or application data are
deleted. Foreground startup/cadence dispatch `Scheduled`, explicit wakes and retry/
continuation dispatch `Requested`. The request-only host never creates a timer.

## Validation scope

Common host tests cover startup, retained wake coalescing, explicit requests, failed
retry, healthy continuation, periodic cadence and the existing exception callback.
Application tests cover a delayed OS delivery after foreground completion, both
consumers sharing a continuation, cancellation, retained failure and a later genuine
periodic scan. Existing recovery race, account change and backoff tests remain.

The emulator-only `sync-trigger` scenario exercises actual WorkManager migration of
an untagged periodic job, preserved job identity, and both dispatch types. It creates
no SDK, account or messages. Source compilation does not substitute for running it.
Product request-count and original-message acceptance must be recorded separately.
