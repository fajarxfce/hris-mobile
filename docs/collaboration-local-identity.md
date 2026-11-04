# Local SDK identity before retained-store transfer

The Android SDK adapter can now read user/device IDs and the public ed25519 and
curve25519 keys from an already-opened, owned client. It closes the temporary
Encryption handle and propagates cancellation. Private keys remain inside the SDK.
The common mapper returns an immutable `CollaborationLocalDeviceIdentity` with a
fingerprint compatible with the HRIS server:

`SHA256(prefix || userId || NUL || deviceId || NUL || ed25519[32] || curve25519[32])`

The UTF-8 prefix is `org.hris.matrix.device-keys.v1` followed by an actual NUL byte.
IDs are bounded printable ASCII without whitespace/control characters; both keys
must be exactly 32 bytes in canonical standard Base64, with optional padding. A
missing/malformed identity fails mapping. Public-key order is fixed. The server
still validates provider signatures and current native/device admission separately.
A local fingerprint is neither a server grant nor remote private-key attestation.

The [local handover adapter](collaboration-runtime-handover.md) now holds a child
lease while reading this identity and pins the observed client before retiring it.
No product workflow invokes handover yet. Before adopting a retained device,
compare the observed identity with the server's admitted original user/device/key
mapping. Keep the original encrypted directory, protected passphrase and store
scope. Substituting a group space ID into the legacy company/native hash opens a
different store; it is not migration. Stop and drain the previous runtime and its
workers before acquiring one new owner. Persist a recoverable explicit alias and
keep pending message transaction IDs and original room ownership. Missing or
mismatched local keys require a distinct new device or the defined encrypted
recovery; never publish replacement keys under the retained device ID.

The [isolated emulator evidence](measurements/collaboration-local-sdk-identity-2026-11-03.json)
uses the actual SDK 26.10.23 and file-store adapter without a reachable provider.
A first instrumentation process creates and closes an encrypted local store. After
an explicit process stop, another process reopens the same identity, detects a
different identity in an empty directory under the same user/device, rejects a
wrong passphrase, and successfully reopens the original store afterward. Competing
file acquisition is rejected; file ownership is released after client close. Only
owned fixture files are deleted and the exact emulator is stopped. The proof APK
is debug-only and must never be installed on the user's phone.

Native execution passes in 74.319s including 30.427s boot. Final root/Android/proof
checks pass with 18 fresh proof tests and reused 4042 desktop, 232 Android-host and 10
build-logic cases; 2727 of those ran freshly in the prior root invocation before a
fixture-only compilation fix. Browser 461 cases pass separately. All checked source
hashes remain unchanged during their runs. Lint reports 0 errors and 47 warnings.
This does not accept an interrupted in-flight migration, group gateway/room/push
ownership, provider messaging, or actual Apple SDK behavior.
