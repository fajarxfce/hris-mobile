# Registered incoming worker on Android

The actual registered application task now has an isolated native acceptance scenario.
The fixture seeds the current application parent and retained original device, then queues
one admitted room. The task uses the real SDK 6 and encrypted application repositories.
The fixture does not call provider/content acquisition directly before the worker runs.

One message is available in local history after the first provider page, while provider EOF
is still false. Two bounded task turns complete the room and its independent wake. There
are exactly two scheduler requests: the initial request and one continuation. Empty-worker
replay and an already-covered head keep that count unchanged. Original text, latest edit,
redacted peer identity and the outgoing journal are checked independently. A second Android
process reopens the identical encrypted history with transport removed and no SDK runtime.

All five native cases pass, preserving the two manual incoming controls and both original
outgoing recovery scenarios. Source/library/APK fingerprints remain fixed; no compiler runs
during the emulator test. The emulator owns fresh user data, uses 2,560 MiB and two cores and stops
after the run. The user's phone and retained personal sender stores are untouched.

The first run failed because the fixture scheduler's allowlist omitted the new incoming
work key. The durable queue survived the failed enqueue; the actual task then reported the
failed continuation. Controlled diagnostics isolated this boundary. The fixture now admits
the key and verifies exact enqueue counts. No product workaround was introduced. The failed
package was retained, so three subsequent tests correctly refused to replace its data.
The next run passed the worker but exposed a second fixture issue: independent scenarios
shared one provider address login budget. A diagnostic run identified provider HTTP 429 mapped to
UNAVAILABLE/collaboration_provider_busy during fixture login. Each scenario now closes its
fictional provider; the next context starts fresh storage and budget with the same login burst limit of 32.
Startup key-readiness failures are retained separately from post-ready assertions. Exact
worker request counts are now checked in JUnit and the independent auditor. All unsuccessful
runs remain in private evidence; no production rate limit or retry rule was changed.

The focused product gate of 615 tests and full product gate of 10,901 tests belong to f46567f4. This increment
adds proof integration, controlled diagnostics and 21 proof cases, plus 43 protocol cases.
The hosted application test filter now also selects CollaborationSpaceIncomingWorkerTest.
Native acceptance does not establish end-to-end realtime, notification delivery or phone
latency. SDK raw hints, first-root/unknown-room discovery, push and new-key handoffs remain
required. All 54 communications PRD acceptance groups remain open; no live rollout occurred.

[Measurements](measurements/collaboration-space-incoming-application-native-2026-11-10.json).
