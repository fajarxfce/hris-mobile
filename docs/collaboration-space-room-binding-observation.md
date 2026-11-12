# Published room binding observation

`CollaborationSpaceRoomBindingRepository.observeByConversation` follows local published
checkpoints and returns the exact authenticated conversation/room pair. It reuses the
bounded finite lookup, cancels obsolete reads when the checkpoint changes and suppresses
identical values. It creates no key, binding, wake, transport request, timer or retry loop.
The existing one-shot lookups retain their original behavior.

Empty and inactive raw scopes return absence, matching the database contract. Current
session and Group admission remain the domain owner's responsibility; absence never
proves that remote history is empty. A malformed pair emits a storage failure while a
later actual publication can recover the read. Cancellation joins pending pair acquisition.

Seven new shared storage cases exercise creation/removal, unchanged values, both binding
origins after encrypted reopen and login renewal, malformed pair recovery, replacement,
obsolete reads and cancellation. The first attempt exposed two incorrect new assertions
about inactive scopes; the corrected checks reject every non-null old-account result and
retain all reopened facts. Production did not change for that correction.

Focused validation passes 35 cases. Full validation accepts 14,738: 12,700 freshly
executed and 2,038 exact unchanged cases. All 14,724 prior test identities remain,
with 14 new JVM/browser cases. Full check, source quality, Android release compile/lint,
shared Wasm/iOS ARM64 compilation and proof assembly pass. No new Apple/native execution
or phone behavior is claimed by this increment.
[Evidence](measurements/collaboration-space-room-binding-observation-2026-11-12.json)
records exact source/report/proof digests and the preserved failed candidate.

The [canonical thread](collaboration-unresolved-space-thread.md) now consumes this reactive
binding inside its existing admission owner, preserving known commands before publication.
Participant/composer context and product UI remain next. Group live rollout stays disabled.

Hosted control43312753298 on exact Mobile1f47eedd passes12,716 portable cases:
6,083 desktop,955 Android host and5,678 browser. Artifact, report and job-log digests
are audited with all prior identities retained, zero replacements and no browser-name
normalization. The compact measurement records this evidence. Apple job131515464968
is still running; its expected5,212 native shared cases are not yet accepted.
