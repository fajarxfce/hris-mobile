# Release92 delivery

Release92 packages the accepted Company thread cancellation fix and canonical
organization history reader from Mobile151438b4. The source gate accepts14,688
cases and all10 SDK8 native scenarios with the exact current-source proof APK.

Both product ABIs are nondebuggable release builds with the previously installed
dev signer, correct Firebase/app identity, private API and SDK26.10.23-hris.8.
Lint reports zero issues. Independent checks verify16KiB ZIP/native alignment,
compiled backup exclusions and identical executable DEX across both ABIs.

The actual x86 release starts on fresh isolated emulator data and reopens in a new
process, with both processes remaining alive for10seconds. Cold/reopened launches
measure2908/2145ms; both screenshots show the complete onboarding page. This is
startup evidence without an authenticated employee account, not a chat latency claim.

ARM64 APK124,333,455bytes has SHA256
`d8001bd137fdd08d3d3b112519eef226ee38ba78c35c6b29cba51b019fc3700c`.
[Exact evidence](measurements/collaboration-release92-2026-11-12.json) records both
artifacts, source, native suite, screenshots and startup. The original20:26UTC connection
timed out; installation subsequently succeeded on13 November at04:11UTC. The device APK
SHA-256 matched the validated ARM64 artifact, identity/data were preserved, and the app
was neither launched nor driven. A read-only ADB check on14 November at14:27UTC again
confirmed versionCode92 on10.77.77.3:5555. Release92 supersedes uninstalled91.
The measurement's installation record contains the accepted device-byte verification.

Live Group stays disabled. Organization chat presentation, pending-message composition,
Apple SDK runtime and call/meeting acceptance continue separately. All54 PRD groups remain open.
