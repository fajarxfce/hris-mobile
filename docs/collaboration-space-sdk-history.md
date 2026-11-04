# Original queued keys across organization migration

The Android proof preserves the original encrypted SDK database, key fingerprint,
Matrix user/device and Keystore-protected passphrase while replacing only the
current gateway credentials. It uses the product `AndroidCollaborationSdkClientSource`
with Matrix SDK26.10.23. The fixture owner is deliberately separate from the pending
product runtime handover; this does not enable cross-company chat in the application.

Two actual API/PostgreSQL/Synapse/emulator cases pass. In the unknown-peer case the
receiver closes before the sender SDK first exists. In the known-peer case the
receiver sends an encrypted primer first in the same direct room, and the sender
decrypts it. The receiver then closes before the sender creates its first outbound
Megolm message. The sender remains unmigrated and the original room has no group
binding. No private keys are cloned and no plaintext provider message substitutes
for encrypted delivery.

The backend completes native legacy migration and checks hashes of the original
queued encrypted to-device frames before and after completion. A test filter drops
the first admitted group response containing those frames. Initial empty syncs are
valid: at most four manual requests may reach that fault. The driver kills the real
Android process, restores the same store and credentials, and makes exactly one
recovery sync. The identical queued frames replay; the timeline decrypts the exact
body, logical ID, event ID and sender. The server independently observes the target
as `m.room.encrypted` on the wire. Timeline updates include delayed-key decryption
retries; a raw new-message callback is insufficient for this assertion.

Before acknowledging the new sync cursor, the driver kills the process again.
The next instance reads exactly one copy of the target from its persisted SDK
timeline before any sync, then acknowledges the cursor and confirms the original
provider queue is empty. The queue remains intact through response loss and the
first recovery. These acknowledgments are transport progress, not read receipts.

Both cases report the SDK's strict shield as `Red`. Successful decryption here is
not proof of verified/cross-signed sender trust. Historical key-query policy and
device verification UX need their own decisions; do not expand one-time-key claims
or new-message eligibility merely to make historical decryption work.

[Evidence](measurements/collaboration-space-sdk-history-2026-11-04.json): root
`:check`, Android-host, proof unit/assemble/lint pass in133.464s with3,883 unchanged
inputs;18 proof cases execute freshly and unaffected tasks are reused. Two actual
integration cases pass in109.162s with12,388 unchanged combined inputs and the same
APK hash. Each has two process deaths, exact queue replay, encrypted wire evidence
and local-history uniqueness. The owned emulator, reverse forwarding and proof APK
are cleaned up. No product, employee phone or live configuration changes.

The companion backend owns `NativeCollaborationSpaceSdkInteropTest` and
`tool/collaboration_space_sdk.py`. Build `:apps:collaboration-proof:assembleDebug`,
then run that opt-in server test with `HRIS_COLLAB_SPACE_APK` pointing to the debug
APK, `ANDROID_HOME` set, and `HRIS_COLLAB_NATIVE_SERIAL` naming an owned emulator.
The driver refuses an existing proof installation. Never install this fixture APK
on an employee device. Backend fixtures explicitly publish the original room and
temporarily grant/revoke their worker role for provisioning; these are not new
worker-isolation or production room-publication acceptance claims.

Next: integrate current group authority with retained physical SDK ownership,
foreground/background exclusion, durable push/reply routing and native hard-parent
recovery. Keep HR-company ACLs separate from organization conversation ownership.
Actual FCM notification deduplication during old/group pusher coexistence remains
required. All54 communication PRD groups remain open.
