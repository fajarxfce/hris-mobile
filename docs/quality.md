# Quality gates

```sh
xvfb-run -a ./gradlew :check
```

The root check runs the architecture/dependency-direction guard, ktfmt, Detekt and
all JVM tests, including the assembled Compose UI. Detekt uses a pinned CLI in a
separate configuration, avoiding a tool plugin dependency on the application's
Kotlin/AGP versions. Light analysis checks source-level rules; it does not replace
the compiler, lifecycle tests or architecture review. Reports are written under
`build/reports/detekt`. There is no blanket baseline suppressing existing findings. Analysis inputs cover
manual source trees and Gradle scripts; generated files and validation caches are
excluded before traversal. Unchanged inputs reuse the previous reports.

The CI workflow also runs browser contracts and builds Android flavors, Web,
iOS Simulator and Windows. Platform build success does not establish OS permission
behavior, keychain availability or configured Firebase delivery on a device.

## Dependency integrity

Gradle verifies dependency artifacts using the committed
`gradle/verification-metadata.xml`. When intentionally upgrading a dependency,
regenerate the hashes for the affected build targets:

```sh
./gradlew --gradle-user-home build/verification-cache --write-verification-metadata sha256 resolvePlatformArtifacts :check :apps:android:assembleDevDebug :apps:shared:wasmJsBrowserDistribution
```

The separate Gradle user home includes parent/BOM metadata that a warm cache can
omit during generation. `resolvePlatformArtifacts` also resolves the supported
Windows/macOS runtime artifacts and iOS Compose resources without executing them.

Review newly resolved coordinates, repository provenance and checksum changes
before committing. Verification bootstraps trust from that reviewed resolution;
a generated checksum by itself does not establish the publisher's identity.
Run the normal command again without the write flag to enforce verification.
Apple and Windows artifacts may require resolution on those operating systems.
Do not turn verification off to resolve a mismatch or add broad trusted groups.

Keep the npm workspace lock current after changing KMP modules or npm dependencies:

```sh
./gradlew kotlinWasmUpgradePackageLock
```

Behavioral tests cover cancellation/late results, shared token refresh, revoked
sessions, storage failures, cache isolation, stale pagination, offline reads,
validation errors, duplicate submissions and persisted language selection.
