# Retained messages before conversation setup

Each explicit Send retains a distinct peer-addressed original with its exact revision, body and
captured time. The same local transaction advances only the compared recipient editing slot to
an empty next revision and publishes a durable Group recovery wake. Exact replay preserves later
typing and does not replace the wake. A storage response alone is not proof: domain reads back the
same immutable original before returning success. Lost replies never allocate another Send.

The editor and originals share one account/Group encrypted partition, but their raw identities and
authenticated payload namespaces differ. A peer account ID is never used as a conversation ID.
Repository code coordinates raw I/O; source/replacement codecs only map authenticated values.
There are at most 128 retained originals per Group and three conditional storage attempts.
Recovery reads 1..8 originals plus authenticated lookahead, ordered by original ID. This is a
recovery traversal, not a message timeline order. Caller-captured timestamps remain unchanged.
Raw collections are bounded and detached before encryption suspends. Missing content keys over
existing records are not regenerated. Wake capacity/conflicts cannot leave a cleared editor
without its original. Source reads and writes retain their caller's cancellation ownership.

Domain owns local session and Group admission around each I/O. Creating an original requires a
published local contact; it does not request discovery over HTTP. Replaying an exact retained
original does not create another eligibility action or read/clear later typing. Account loss or
confirmed denial stops publication. Text already retained by a completed transaction remains
available for admitted recovery. No repository calls another repository and no UI performs setup.

Fourteen shared domain cases and thirteen real storage-contract methods cover captured values,
replay, lost responses, denial, cancellation, competing writers, restart, 128-message capacity,
bounded pages, isolated owners, key loss, tampered frames and mutable adapter collections.
Desktop uses SQLite, browser uses IndexedDB, and actual application DI resolves the repository
and submission use case. Apple test wrappers are source-compiled; no new Apple runtime acceptance
is claimed. The measurement records exact results and any unsuccessful earlier attempts.

## Remaining integration

This package implements local submission storage, not canonical transfer or native delivery.
The existing worker does not consume this new source yet; no user route has been activated.
There is deliberately no source-removal operation before destination acknowledgement exists.
Keep the same original ID/body/time when preparing the canonical message. Importing must retain
any independent canonical draft and atomically publish the destination original plus its inbox
activity. The existing canonical composer handoff clears an editor and must not be reused to
erase unrelated typing. Keep its two-partition transaction bound. Recipient acknowledgement is
a later conditional write after exact destination proof; interruption leaves a replayable source.
Persist the target binding before retirement so changed metadata cannot reroute an uncertain send.

The user requested the next build on the phone on14 November. Before changing source again,
finish release93 from .work/release-phone-2026-11-14-build93: current-source SDK8 proof suite,
actual product startup, both ABI verification, then announced install-only delivery. The Group
native runner is prepared in .work/recipient-outbox-release93-2026-11-14; compile its host before
starting the isolated emulator. These new native/release gates are not yet executed here.

Then implement bounded canonical transfer and its restart/race tests, pending-recipient inbox
projection and the new-contact entry. Reuse the swipeable chat hub, root-owned contact state,
and independent Group stack. Group/legacy selection needs an explicit rollout input; absent or
denied Group state cannot silently enable legacy chat. Complete company membership filtering
remains separate from the eight public labels. Keep AppViewModel at the window and Company
navigation within the Group root's lifetime. HRIS recipient policy is not a generic Starter port.

Phone92, live GroupOFF and undeployed V117–140 remain unchanged. All 54 communications acceptance
groups remain OPEN. Preserve the blocked SQLite fixture payload and failed hosted Apple evidence.
The phone was rechecked read-only on14 November at14:27UTC: versionCode92, no launch/install.
The original transport retry fix e82abe16 and idle SDK retention fix 0f4f0039 are both ancestors of
its source151438b4; see collaboration-delivery-wake-ownership.md and collaboration-runtime-renewal.md.
Release73's bounded native idle capture had zero settled transport/setup/room/default-key requests;
that is historical evidence, not a fresh capture of the user's phone session on14 November.

[Exact validation](measurements/collaboration-recipient-outbox-2026-11-14.json).
