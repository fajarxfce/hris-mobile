# Android Matrix SDK distribution

The application uses `dev.fajar.hris.matrix:sdk-android:26.10.23-hris.2` through an
exclusive Ivy repository and strict checksum verification. The [SDK release](https://github.com/fajarxfce/hris-mobile/releases/tag/matrix-sdk-26.10.23-hris.2)
contains the AAR, descriptor, source bundle, license, build record and checksums.
The [normal dependency gate](measurements/collaboration-sdk-baseline-shipping-2026-11-06.json)
passes with 7,026 tests retained after a formatting-only correction and zero
Android lint findings. Candidate runtime acceptance is recorded separately below.

This version preserves runtime detection of optional ARM64 CPU instructions.
SDK 1 inherited Cargo flags forcing AES/SHA2/PMUv3; release 44 crashed with SIGILL
while importing its existing encrypted store under Android ARM64 translation.
[The rejected release](measurements/collaboration-release44-2026-11-06.json) remains
rejected, and SDK 1 assets are unchanged. Its release notes describe the limitation.

The replacement passed [retained-store acceptance](measurements/collaboration-release45-2026-11-06.json)
in nondebuggable candidate 45: two offline and two online room openings, followed
by a stable-process wait. The original data and keys were retained. Two further
ARM64 native scenarios passed encrypted messaging, process restarts, offline
settlement of a server-confirmed original and repeated recovery without another
send. Each scenario recorded three encrypted sends and zero redactions.
[Build, runtime and publication evidence](measurements/collaboration-sdk-arm64-baseline-2026-11-06.json).
Candidate 45 uses a local dependency override and is not the normal phone build.

The Android adapter needs two additions to the Matrix Rust SDK: reading an
original event without losing fields, and settling a proven server-confirmed
original in a paused local send queue. Application policy must prove the accepted
content and owner; the SDK does not authenticate an arbitrary supplied event ID.
See the [recovery contract](collaboration-confirmed-queue-recovery.md).

The [build manifest](../tool/collaboration-sdk-patches/android-build.json) pins the
upstream revision, candidate patches, toolchains and generated Kotlin hashes. The
[distribution manifest](../tool/collaboration-sdk-patches/android-distribution.json)
pins the Kotlin wrapper and upstream dependency POM. Follow the
[packaging instructions](../tool/collaboration-sdk-patches/README.md#four-abi-candidate-packaging)
to produce the assets. Every changed binary needs a new version; never replace a
published asset. Verify both descriptor and AAR hashes before accepting a dependency.

SDK 2 rebuilds ARM64. The other three native binaries are reused unchanged from
SDK 1: production sources, Cargo.lock and their effective target configuration
match. All nine generated Kotlin files and the compiled class archive are also
unchanged. Every packaged library passes ABI, entry-point and alignment checks.
The 16 KiB requirement applies to the two 64-bit ABIs; NDK 28 ARMv7/x86 outputs
use 4 KiB alignment. The descriptor preserves upstream dependencies, including
JNA's Android AAR. Fresh ARM64 SDK CI passed with verified source/binding hashes;
its independently built native file differs from the locally tested release file
and is not substituted into this package.

[Application source CI](measurements/collaboration-confirmed-queue-ci-2026-11-06.json)
records the preceding application commit's protocol, native HRIS, portable and
Apple checks. Its native scenarios executed x86_64; the SDK 2 ARM64 runtime proof
above is separate. Physical phone send latency, physical 32-bit execution and R8
execution are not claimed. Existing application release variants do not enable
R8, and Apple's SDK dependency is unchanged.

The current build manifest additionally includes the unshipped refresh-backoff
candidate. It preserves retryable token refresh failures and the native Retry-After
classification. [Candidate evidence](measurements/collaboration-sdk-refresh-retry-2026-11-06.json)
does not replace SDK 2 acceptance or authorize reuse of its version/assets.
