# Android Matrix SDK distribution

The application currently pins the local candidate
`dev.fajar.hris.matrix:sdk-android:26.10.23-hris.6`, built from the separate
`android-refresh-coalescing-build.json` manifest. Its four original CI artifacts,
matching Kotlin bindings, source hashes and packaged native libraries are verified.
[SDK6 evidence and outstanding acceptance](collaboration-sdk-refresh-coalescing.md).
Android consumer checks pass; native validation and publication are pending; a clean remote consumer
cannot resolve SDK6 from GitHub yet. Use `-PmatrixSdkRepository=PATH` with the verified
local Ivy candidate repository until that exact version is published.

The last published prerelease is
[SDK4](https://github.com/fajarxfce/hris-mobile/releases/tag/matrix-sdk-26.10.23-hris.4).
SDK5 added optional HTTP observation and remains a retained local candidate. Existing
published files and SDK5 binaries must not be replaced. The sections below record
historical SDK1–3 acceptance, not SDK6 runtime or publication status.

## Historical distribution acceptance

SDK 3 used `dev.fajar.hris.matrix:sdk-android:26.10.23-hris.3` through an
exclusive Ivy repository and strict checksum verification. The [SDK release](https://github.com/fajarxfce/hris-mobile/releases/tag/matrix-sdk-26.10.23-hris.3)
contains the AAR, descriptor, source bundle, license, build record and checksums.
SDK 3 preserves retryable token-refresh failures and their Retry-After information;
HTTP 429/408 must not become rejected sessions. Permanent denials remain enforced.
All four native ABIs were rebuilt; the nine generated Kotlin bindings and compiled
class archive remain unchanged. Core, HTTP and FFI regressions pass, together with
two ARM64 encrypted-message, process-death and offline queue-recovery scenarios.
[SDK 3 build, native acceptance and publication](measurements/collaboration-sdk-refresh-retry-2026-11-06.json).

[Candidate 49](measurements/collaboration-release49-candidate-2026-11-06.json) passed
retained-store smoke and ordinary expired-token renewal after actual company
readmission. Its first offline attempt exposed a separate application workspace
freshness bug, so candidate 49 is not a phone release. The normal dependency/full
application gate must include that fix before the next phone update. No429 was
observed in the successful candidate runtime sample; SDK regression tests exercise
that response. The earlier [SDK 2 normal dependency gate](measurements/collaboration-sdk-baseline-shipping-2026-11-06.json)
remains historical acceptance for SDK 2 only.

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

The historical default build manifest includes the SDK 3 refresh-backoff patch.
Every version retains its own source and acceptance evidence; SDK 1 and 2 assets
remain unchanged.
