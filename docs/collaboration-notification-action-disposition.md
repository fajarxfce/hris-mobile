# Authenticated notification actions and durable dismissal

`CollaborationNotificationActions` authenticates the sealed action against the
current native session and retained company/conversation facts, then matches its
kind and complete card/render reference to the native input. An outer Mute, Read
or Open identifier cannot retarget a differently typed sealed action. Reply text
uses its separate protected-input capture path.

Open returns a captured conversation target only. It does not queue a message,
mark anything read or remove a notification. The eventual route must enforce its
own account/company admission; actual visible-message coverage decides reads.

Read, mute and dismiss run under the existing native surface owner. A point read
returns only the exact journal reference, with current-parent checks before and
after I/O. A superseded render cannot be acted on through this path. Read and mute
use the existing durable command policies. Their local completion does not prove
network receipt delivery. Native removal and journal retirement compare the exact
render and version; a newer card survives stale cleanup and compensation.

Dismiss first records `Dismissing` in the encrypted journal, without preview text
or action tokens. That record retains a slot in the bounded pending index and
remains discoverable after an uncertain write or process restart. The issuer cannot
republish its original event. Native cleanup then removes the exact render and
finishes the record as `Dismissed`. A newer acquired original may replace the old
watermark; an old cleanup reference cannot retire the newer render.

The reconciler handles `Dismissing` as metadata-only cleanup before attempting any
content publication. It therefore completes an already-owned dismissal after a
company content denial or same-account native-family replacement. Creating a new
dismissal request still requires the current issuing parent. Prepared and Published
remain the only displayable states. Pending discovery additionally includes
Dismissing, while the Prepared-only filter excludes it. Existing schema and keys
are reused; the encrypted format adds the new state name.

Focused validation passes61 desktop cases/seven suites, including real Koin/SQLite
integration, with45 fresh cases/six suites. An initial test incorrectly expected
caller cancellation when the surface owner itself closed; the existing contract
correctly returns Unauthorized after joining its operation. The expectation was
corrected and a separate caller-cancellation/retry test verifies no duplicate read
intent. Product cancellation policy was not relaxed.

Full local validation passes in459.096s:3,916 desktop,230 Android host,16 actual
browser and ten build-logic cases, zero failures/errors/skips. Root quality, Android
assembly/lint and shared Wasm pass; lint zero issues. The16 source hashes remained
unchanged. [Evidence](measurements/chat-notification-actions-2026-11-03.json) records
2,326 freshly executed cases/283 suites and reused reports separately.

Native nonreply receiver,
reply spinner acknowledgment and Activity navigation are not connected yet. The
OS display in these application tests is simulated; actual Android callback proof
for these actions remains required. Production hosts stay unbound and chat push
stays off. This increment does not add voice, video or meeting UI.
