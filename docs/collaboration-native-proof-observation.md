# Seeded observation in the native HRIS proof

The Android proof seeds a fictional HRIS login and workspace, then uses the actual
control HTTP adapters and owned SDK flow. Its session and permitted workspace
observations must remain active after the initial snapshot until their collector
is cancelled. They do not simulate login renewal or workspace mutation. Background
proof still forbids selected-workspace reads and observation.

The previous one-element flows completed immediately. Production notification source
observation correctly treats completion as unavailable, so the fixture violated the
continuous-source contract. The [hosted failure](measurements/chat-priority-notification-native-failure-2026-11-03.json)
records three worker failures in priority processing after 175 seeded messages.
Correcting this fixture does not weaken production completion guards or establish
full native acceptance; the corrected hosted run must show whether other failures
remain.

Three host regressions reproduce premature session/workspace completion and verify
initial values, re-observation and joined cancellation. The background case preserves
the selected-workspace prohibition. Two diagnostic cases verify a fixed notification
failure category and that unknown provider codes/messages cannot enter evidence.
Additional notification surface, identity and priority categories use a fixed
allowlist; raw errors and credentials remain excluded.

These changes affect only the isolated Android proof. No production native receiver
or iOS delegate is activated, chat push remains disabled, and the user's release20
installation is unchanged. Notification-input OS-death acceptance and actual native
chat UI delivery remain separate work.

[Validation](measurements/chat-native-proof-observation-2026-11-03.json): the initial
regression fails four of five cases in38.263s; after correction all five pass in51.190s.
Full checks pass in121.630s with11 fresh proof cases/three suites, plus explicitly
reused3,766 desktop/532,230 Android-host/37 and ten build-logic/two. Four source hashes
are unchanged during checks. Proof assembly and root quality pass; lint has zero
errors and the same47 warning categories/counts as the tracked prior baseline.
