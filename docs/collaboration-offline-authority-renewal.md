# Chat delivery after stale authority

Release54 retained the original offline message but did not send it within90 seconds
of reconnection. Returning the same process to foreground triggered recovery. Local
history and pending/draft previews remained readable. That failed device probe is
preserved in [its evidence](measurements/collaboration-release54-probe-2026-11-07.json).
The exact rejected runtime code was not captured. Release55 acceptance below tests
the resulting reconnect behavior without claiming that missing diagnostic evidence.

The Android foreground worker discovered retained commands but returned Blocked when
selected-workspace or company-policy authority was stale. App foreground refresh is
finite and does not poll after a transient failure. Removing authority checks would
allow delivery with stale permission; retaining local facts is a separate decision.

The application worker now coordinates one targeted RefreshWorkspace call only for
client.workspace_expired or client.company_policy_expired, then retries setup once.
The typed target captures local session, workspace, audience, account and company.
A changed/missing owner returns Blocked before renewal I/O. Existing native-family,
full-session and final workspace CAS checks still guard publication. The worker
rechecks captured command ownership after renewal. A network failure remains Retry,
so the existing owned scheduling paths can resume the same work. A confirmed denial
remains gated. Background notification admission never enters this selected-workspace
renewal path. No ViewModel polling, new IDs, queue replacement or fake ACK is added.

Tests exercise expired policy and workspace authority through the actual app container,
encrypted command storage and worker. Offline renewal retains the original command
without opening SDK delivery; later renewal delivers exactly once. A newly disabled
module keeps the original record but never opens SDK. Core tests cover each changed
target coordinate, missing owners, replacement during I/O and canceled late results.

Focused82722 passed52 cases/5 suites/376.389s. Full60955 passed5,229 fresh cases/
657 suites/589.990s; both had4,414 unchanged inputs and zero failures/errors/skips.
Root checks, Android host/release lint, formatting, architecture, Detekt, shared Wasm
and40 actual Workspace Browser cases pass. Explicit workspace tests and app-worker
coverage/report upload were added to portable/Apple CI after focused checks; no Kotlin
change occurred between gates. Both jobs are reaped. [Exact source and reports](measurements/chat-offline-renewal-2026-11-07.json).

Release55 source95e35b9d now demonstrates automatic reconnect with the same original
Bima store, without manually foregrounding or replacing the offline intent. After
network restoration and opening the room, it is observed Pending at3.069s and Sent
at5.326s (coarse UI polling, not transport timing). Pending/draft previews and composer
restore survive two cold offline re-entries. The same process remains alive with no
fatal crash since the probe started. [Exact build/device evidence](measurements/collaboration-release55-2026-11-07.json).
The validated APK55 is installed in place on the phone; original data and app identity
are preserved. Composer keyboard dismissal, initial viewport clipping and final
phone latency remain open. Do not rewrite the failed54 probe as passed.
