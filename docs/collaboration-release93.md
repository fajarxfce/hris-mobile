# Release 93 delivery

Release 93 packages accepted mobile source 2eb833f460cf46fa2f1170bbb1656b5cd9144f27. It updates Compose from 1.9.3
to 1.10.3 and carries the accepted navigation, local history/inbox and recipient editing/submission
foundations. The new Group product routes remain disabled. This is not calling or meeting acceptance.

The first release93 candidate was withheld after the real product activity crashed on missing
Compose locale assets. The shared convention now enables Android resources, and both generated
language files are checked byte-for-byte inside both accepted APKs. The earlier failed startup
remains recorded in android-compose-resources.md; no failing candidate was installed on the phone.

The source gate validates 17,931 cases. Ten actual SDK 8 scenarios pass with the
current-source proof APK on a fresh isolated emulator, including account replacement, encrypted
originals, late keys, receipts and offline reopening. Two late-key scenarios each include a
30-second settled observation with zero non-sync requests and three normal long polls.
No compiler ran alongside that emulator.
The actual nondebuggable x86 release also cold-starts and reopens in another process, stays alive
for both ten-second observations and has inspected onboarding screenshots. No authenticated
employee-account latency claim follows from those startup checks.

Both release ABIs share executable DEX and the previously installed dev signer. Verification
covers private API and Firebase identity, SDK 8 binary provenance, 16 KiB alignment, compiled backup
exclusions, absence of fixture endpoints and zero lint issues. ARM64 has SHA256
`1097c710ff0296282f5a9113b7a4b7412017758e233d42d9b189db4ced14f07c` and size 125,491,323 bytes.

The authorized phone 10.77.77.3:5555 was updated in place to versionCode 93 at
2026-11-14T16:44:09.605120+00:00. The device APK hash matches those validated bytes.
The existing app UID, first-install identity and data were preserved. No uninstall, data clear, UI launch or UI automation was performed. This installation is not a fresh phone traffic or send-latency capture.

The original repeated-delivery fix e82abe16 and runtime-retention fix 0f4f0039 were already
included in 92. Their source and native evidence remains in collaboration-delivery-wake-ownership.md
and collaboration-runtime-renewal.md. They remove delivery wakes on passive timeline/permission
renewal and preserve the active Matrix client across ordinary permission freshness changes.
Real denial, parent expiry and account replacement still retire the affected runtime.

Live Group remains OFF and V117–140 are undeployed. All 54 communications PRD acceptance groups
remain OPEN. Continue canonical recipient handoff, pending inbox projection and product routing,
then the remaining chat, voice, video and meeting plan. Native Apple execution and the blocked
SQLite fault-fixture payload keep their existing separate status.

[Exact validation and installation](measurements/collaboration-release93-2026-11-14.json).
