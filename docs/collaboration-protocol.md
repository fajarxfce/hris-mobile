# Native collaboration protocol proof

The accepted [full collaboration PRD](../../hris-platform/docs/collaboration/prd.md)
adds employee chat, calls and meetings. This is the first Android interoperability
proof, not the HRIS product integration. See Platform's delivery ledger for all
54 feature groups and P0–P8 acceptance.

`apps/collaboration-proof` builds a separate debug-only APK with no launcher,
Firebase configuration or HRIS app dependency. Its instrumentation restores a
fictional Matrix session, joins an encrypted room, decrypts a browser message and
sends an encrypted reply. Every client, sync service and callback has bounded
ownership and cleanup. The app accepts only a loopback lab server and fictional
identity. The temporary store passphrase is lab material, not a production
Keystore adapter. The release variant is disabled.

The dependency is Matrix Rust Kotlin SDK **26.10.23**. Its AAR/module hashes were
checked against Maven Central's SHA-256 files. Transitive JNA **5.18.1** matched
Central's published SHA-1 files; both artifacts are pinned locally with SHA-256.
Gradle dependency verification stays enabled. Sources:

- https://github.com/matrix-org/matrix-rust-components-kotlin/releases/tag/sdk-v26.10.23
- https://repo.maven.apache.org/maven2/org/matrix/rustcomponents/sdk-android/26.10.23/
- https://repo.maven.apache.org/maven2/net/java/dev/jna/jna/5.18.1/

With JDK 21 and Android SDK 36 configured, build:

```sh
./gradlew :apps:collaboration-proof:assembleDebug :apps:collaboration-proof:lintDebug --no-daemon
```

Boot the existing `hris-mobile-dev` emulator on port 5560 using
[the development procedure](development.md#headless-android-emulator). From the
Platform checkout at `a9100ece`, run:

```sh
python3 tool/check_collaboration_providers.py --docker-sudo \
  --android-serial emulator-5560 \
  --android-apk ../hris-mobile/apps/collaboration-proof/build/outputs/apk/debug/collaboration-proof-debug.apk
```

The driver refuses physical devices, other AVDs, existing proof packages,
unrelated APK identities and conflicting reverse ports. It serializes owned
emulator use, sends credentials through stdin into app-private files, and removes
only the owned APK/mapping on completion. Do not manually install this fixture on
a phone. Existing HRIS apps and accounts are not used or changed.

Full root checks and debug lint passed in 4m42s: 2,002 desktop cases across 349
suites, no failures/errors/skips. Lint reports no errors; its 45 warnings cover
dependency update notices and the intentionally minimal fixture packaging.

The actual Android 35/Web combined run passed both protocol scenarios in 24.590s,
with no failed/skipped cases and successful cleanup. Both endpoints decrypted the
other endpoint's message; corresponding server events remained encrypted. See
[source and artifact evidence](measurements/collaboration-android-protocol-2026-10-31.json)
and the linked Platform result for the browser/media checks. Hosted workflow
`Native collaboration protocol` builds this source and uses a fixed Platform
commit. Its result must be recorded separately; workflow source alone is not a
passed CI run.

Next gates include protected HRIS company/device sessions, native durable queue
recovery, verified devices, Apple bindings, MatrixRTC media-key distribution and
native background calls. None is inferred from the encrypted text exchange.
No reusable product foundation has yet qualified for a Starter port.

## Apple binding gate

`tool/collaboration-apple-proof` pins Matrix Rust Swift **26.10.27**. The upstream
package validates the binary XCFramework SHA-256
`399af06c14c7fdfa86e3bb4d36cf50145d17ae2a20c30edad06d2011a20d9bb6`.
The `Apple collaboration binding` workflow executes the actual Rust device-key
store on macOS, reopens it with the original key, rejects a different key, and
compiles the iOS device slice. It does not start synchronization or contact a
real provider. Successful binding tests will not establish Apple E2EE interop,
CallKit/PushKit acceptance or a physical iPhone test.

This SDK requires iOS 16. The isolated package adopts that minimum; the existing
HRIS app remains iOS 15 until its collaboration integration explicitly changes
and validates the deployment target. Linux cannot execute this Apple binary;
record exact hosted results before calling this gate verified.

The actual Apple workflow passed at `e210508a` in
[41182072489](https://github.com/fajarxfce/hris-mobile/actions/runs/41182072489):
one native recovery/rejection case (0.051s) and a successful iOS device build,
using Xcode 16.4 and Swift 6.1.2. The resolved SDK revision is committed in
`Package.resolved`; [evidence](measurements/collaboration-apple-binding-2026-10-31.json)
records the tested source, binary checksum and limited acceptance scope.

Android hosted verification also passed at `eefc4121` in
[41182128950](https://github.com/fajarxfce/hris-mobile/actions/runs/41182128950).
Both actual provider scenarios passed (no failures/skips), with full cleanup in
66.016s. [The hosted result](measurements/collaboration-android-ci-2026-10-31.json)
retains the CI APK hash, source revision and fixed Platform driver separately
from the local APK evidence.
