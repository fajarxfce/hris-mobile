# Owned local conversation command observation

`ObserveCollaborationSpaceConversationCommands` exposes the account/space's bounded
active command inventory through the existing repository. It owns session observation
and cancels the previous command collection when the account, local login or API
ownership changes. It buffers no stale queued values across those changes and
collapses equivalent snapshots. Disposing the collector stops its subscriptions.

Ordinary token refresh and native-parent rotation do not change local account/space
ownership or restart this collection. Credential age and selected HR company cannot
hide retained Pending, Unconfirmed or Recorded setup facts. A new login for the same
account may create a new owned subscription to the unchanged durable commands.
Session/storage failures are returned without allocating commands or initiating retry.

The use case has no clock, HTTP, worker or SDK dependency. It never reopens directory
access, archives a result or interprets a setup receipt as room readiness or message
acknowledgement. Pages and ViewModels receive this local flow; actual preparation,
dispatch, local canonical projection and product routing remain separate work.

The data module supplies a factory binding. The actual application binding test
resolves it with the new encrypted command repository and existing protected key
registration. Domain tests cover expired native state, token refresh, account/login/
audience replacement, backpressure, cancellation, disposal, storage failures and
reading owned setup facts while directory access remains denied.

## Validation

[Source and results](measurements/collaboration-space-conversation-observation-2026-11-07.json):
focused75614 passed seven domain cases in104.606s; the unchanged two application
binding cases from the prior run are identified as reused. The first focused gate
failed only a nested generic assertion in test compilation and is retained separately.
Full49430 passed3,593 fresh cases/447 suites in466.030s on4,371 unchanged inputs,
with zero failures/errors/skips or Android release lint issues. Root check, Android
host tests, formatting, architecture, Detekt, actual Browser observation tests and
shared Wasm compilation passed. Apple CI for this exact source is not yet accepted.
No new APK was built and the phone's reported delay remains unresolved.
