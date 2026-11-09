# Original organization Room dispatch

`ReplayCollaborationSpaceRoomRequest` performs one explicit attempt of an already
retained Room command through the [stable request contract](collaboration-space-room-stable-intent.md).
Current native credentials and client build are admission, not a new request identity.
Preparation, non-execution fencing, canonical room publication
and SDK delivery remain separate responsibilities. The use case coordinates the
existing command, directory, session and room repositories; no datasource, ViewModel,
SDK registry or scheduler owns this policy.

Recorded and Fenced originals return as locally owned facts, including after native
expiry or directory restriction. A Fenced original cannot be sent again. Pending and
Unconfirmed commands preserve their operation ID, original binding, device/version,
conversation, peer and observed participant/revision. A fresh current native parent
supplies transport authentication without changing that immutable request.

Before HTTP, exact compare-and-set marks uncertainty and increments the bounded
attempt count. The actual normalized stored mark is read back and matched before the
one request. Failed, contended or uncertain marks do not dispatch. No internal loop,
new operation allocation, implicit successor, room polling or provider-key action is
introduced. The server still performs current authorization/provider admission.

The finite attempt checks wall time, monotonic time and the earliest observed native
hard expiry; same-family renewal cannot extend its 15-second budget. Ownership is
checked after suspended operations. At most 1,000 explicit attempts are permitted and
both mark/outcome revisions need capacity. Existing resolution remains available when
send-attempt capacity is exhausted.

An observed response has at most 2 seconds of cancellation-independent durable capture.
Only the exact conversation and bounded receipt revision can become Recorded. This
receipt is an original setup result, not proof of room readiness, content access or
message delivery. Native expiry after receipt observation does not erase that owned
fact. Caller cancellation propagates after capture. An interrupted local write only
recovers from an exact retained outcome; competing terminal facts are not overwritten.

Peer failures stay with the original without closing other contacts. Global failures
restrict the directory through cancellation and failed outcome persistence. Explicit
original replay can obtain a receipt under current server admission, but it does not
clear a directory denial or reopen a content projection. Failed retries retain the
strongest observed denial.

The data module binds the use case; the assembled application container resolves it.
New domain cases cover loss, cancellation, owner changes, competing outcomes, denial,
clock/attempt limits and no replacement. The shared actual-storage contract adds
SQLite/IndexedDB/Apple-target cases for normalized marks, immutable ciphertext, key
continuity, parent/database reopening and lost/cancelled outcome writes.

[Validation](measurements/collaboration-space-room-dispatch-2026-11-09.json): focused
76 cases pass in 263.919 seconds. Full gate passes 5,963 fresh cases (5,364 desktop and 599 Android
host) in 902.068 seconds, with no failures/errors/skips or Android release lint findings.
Root check, Android release compilation, Wasm/iOS source, formatting, architecture and
Detekt pass. All 4,532 app inputs and the public SDK6 inspector inputs remain unchanged.
Actual Chromium passes 74 cases in 115.038 seconds, including all 18 replay cases and 11 shared
IndexedDB storage cases. Reused XML is recorded separately, never added to fresh totals.
Apple source compilation does not establish execution of these new cases on Apple.

No new APK, product route or backend rollout is introduced. Next are published room
binding/projection, exact LEGACY metadata validation, original SDK delivery/history
and organization routing.
