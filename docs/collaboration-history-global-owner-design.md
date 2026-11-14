# Global history progress — reviewed draft, not activated

Current status: 23 reviewed/formatted UNAPPLIED source paths, 57 new shared cases. Focused59 and
full17372 gates, recording and commit scripts are prepared but UNRUN. Prerequisite owner inputs
full17258 is locally accepted; commit/push before applying the global draft. Source changes and application/consumer activation are separate gates.

## Durable state and finite continuation

Retain latest observed trigger, completed pass ID/trigger, and one captured active pass. Trigger
facts are local history signal, catalog version, completed command-repair demand and destination
index generation. Physical index revisions do not request another pass. All-null is a real first
observation; completed unchanged reopen is quiet. Later demand cannot relabel an active interval,
and later work must not reuse a completed pass ID. Destination replacement requires a fresh pass.

Catalog and source traversals retain up to sixteen canonical IDs before source visits. The next
cursor advances after each queued item has a durable outcome. Catalog empty pages with a next
cursor continue; source empty pages with a next cursor are rejected. Opaque cursors are bounded.
One failed source does not block healthy sources in the captured page or later source rounds.

A catalogDeferred flag survives page completion, healthy source progress and process restart.
Missing preparation or a lost catalog continuation cannot disappear merely because that source
is absent from the prepared-source enumeration. The pass parks instead of falsely completing.
Explicit owner recovery or real new demand starts a fresh catalog attempt and clears the flag;
unchanged observation does not. Required DTO fields reject malformed stored state.

Useful progress requires an authenticated before/after source position; an unknown before state
and a raw Restart alone cannot justify another automatic round. Healthy advancement permits
another fair round; failed-only rounds park. A newly coalesced trigger may be consumed once without
rewriting the pass's original captured trigger. Ready cannot coexist with deferred preparation.

## Atomic storage and ownership

Preserve the existing raw TWO-operation limit. A mutation checks one exact source checkpoint and
writes global progress, or checks destination metadata and writes global progress. Successful
source acknowledgements use the source guard. Capture, deferred outcomes and final completion use
the destination guard. Expected global generation/revision is conditional in the same transaction.
Never lift the limit or claim that an earlier source read alone fences a later global write.

The distinct encrypted owner partition authenticates account, audience, Group and physical
version; it is not a canonical history source. Metadata-only destination observation does not
read inbox rows. Missing metadata creates no key. Queue DTOs and admitted input/output snapshots
are detached before suspension. Raw repository access uses ReplicaStore, cipher and credentials;
there are no repository-to-repository or use-case-to-use-case calls.

Admitted read/change/observation preserve denial latching, current account/Group ownership, token
rotation, cancellation and late results. Failed/uncertain mutation is not retried implicitly.
No-op mutations still inspect actual expected state and their guard. Opaque trigger identities
cannot order old requests: after contention the future owner must reacquire current observations.

## Verification drafted

43 domain cases cover state, queues, transition guards, admitted reads/writes and owned metadata
observation. Fourteen actual-storage contract cases cover reopen, exact two-operation mutation,
source/global/index races, lost responses, cancellation, keys, malformed ciphertext and ownership.
Desktop/browser execute the shared cases; the Apple wrapper is a compilation gate only here.
Two existing DI tests resolve the repository and three added use cases without starting work.
The new deferred flag also roundtrips through actual storage and reopening.

All gates remain UNRUN for this draft. Previous failed hosted Apple evidence and the unrelated
blocked fixture preparation payload remain unchanged. Do not recreate that blocked payload.

## Next application integration

Integrate bounded history turns into the existing account/Group repair lifetime, preserving
command service and one typed repair-progress subscription. Keep business selection/continuation
policy in domain; workers own jobs and route admitted outcomes. Do not call use cases from another
use case or move orchestration into UI/VMs. Read actual source state before each visit, finish older
source windows before preparing a later pass, and retain unresolved references and current cursors.

Own and cancel/join metadata/history/catalog collectors on account replacement. A second permanent
per-room observer, polling loop, native client or network acquisition is unnecessary. Yield after
finite useful work so new commands are serviced. Explicit owner restart is distinct from a
self-produced progress notification. Test fairness, finite restarts, failed rooms, cancellation,
current denial, and interrupted recovery before activation. Incoming completion, thread/list/UI
consumers and SDK8 Apple app activation still need end-to-end evidence.

Phone92 and live deployment remain unchanged. GroupOFF; V117–140 undeployed. All54 PRD acceptance
groups remain open. Continue beyond checkpoints and push every commit, one compiler OR emulator.
