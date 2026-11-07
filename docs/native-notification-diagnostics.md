# Retain the first native notification failure

The original x86 CI run `42336761242` failed while confirming removal of an
Android notification. Its `IOException` and source/artifact evidence remain in
[the first-run record](measurements/collaboration-credentials-native-ci-2026-11-07.json).
The OS timing cause is not established. The production adapter and its removal
deadline and neutral-card timeout were unchanged in that diagnostic-only increment.
A later [explicit retirement correction](native-notification-retirement.md) uses the
new delayed-expiry evidence while preserving both confirmation bounds and OS timeout.

The isolated native probe now retains the primary exception through fixture cleanup
and records the failing phase before removing its own notifications. The bounded
snapshot includes completed-case count, elapsed time, notification permission and
power state, plus fixture marker presence, post age and timeout. It excludes message
content, reply inputs, credentials, raw notification tags and account identifiers.
The timeout getter is guarded for API 26; the proof application supports API 24.

The Python driver archives a strictly validated subset of that snapshot before
uninstalling its owned package. Diagnostic-read, cleanup and final-report failures
cannot replace the original failed assertion. Instrumentation reports at most four
suppressed exception class names. Package absence, exclusive emulator ownership and
cleanup assertions remain required.

[Validation](measurements/native-notification-diagnostics-2026-11-07.json) includes
33 Python tests, the final compile/build/lint/architecture gate and an actual Android
proof on fresh isolated userdata. The Gradle gate reused its existing test outputs;
no new XML test cases are claimed. Proof lint reported no errors and 49 warnings.
The native run passed permission denial, 15 raw notification cases, four runtime
composition cases, process-death input recovery and all five action callbacks,
including a reply through System UI. The fixture was uninstalled and its emulator
stopped with transport absence verified. No provider messages, original Bima data or
phone UI were involved. This successful local run does not resolve the original
intermittent CI failure or establish physical-phone send latency.
