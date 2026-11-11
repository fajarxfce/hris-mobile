# Android Matrix SDK distribution

The application now pins `dev.fajar.hris.matrix:sdk-android:26.10.23-hris.8`, built from
`android-incoming-hints-build.json`. [SDK8](https://github.com/fajarxfce/hris-mobile/releases/tag/matrix-sdk-26.10.23-hris.8)
contains the AAR, descriptor, source bundle, license, build record and checksums. All four ABIs,
generated Kotlin, source pins, compiled APIs and ELF alignment requirements are verified.
Six remote asset digests and downloaded hashes match, as does the unauthenticated descriptor.

[Native acceptance](collaboration-sdk-incoming-hints-native.md) covers actual stored-key callbacks,
exact sync/store positions, joined cancellation and all five prior encrypted delivery/recovery
controls. The new scenario adds no requests. The isolated application gate passes 10,901 fresh
cases plus 100 exact unchanged database browser controls. The primary source matches all 4,864
accepted inputs. Wiring these SDK hints into durable application recovery remains next.

SDK7 stays retired; SDK6 and earlier published artifacts are immutable. Existing phone builds
and the pending phone 89 APK remain on SDK6. Apple native SDK8 and phone latency acceptance
remain separate. Reusable inspector source is unchanged.

## Previous SDK6 acceptance

The application pins the published
`dev.fajar.hris.matrix:sdk-android:26.10.23-hris.6`, built from
`android-refresh-coalescing-build.json`. Its four original CI artifacts, matching
Kotlin bindings, source hashes and packaged native libraries are verified.
[SDK6 release](https://github.com/fajarxfce/hris-mobile/releases/tag/matrix-sdk-26.10.23-hris.6)
contains the AAR, descriptor, source bundle, license, build record and checksums.
All six remote asset sizes/digests match and the public descriptor was fetched and
verified. A local repository override is optional for reproducing candidate builds.

[SDK6 evidence and remaining work](collaboration-sdk-refresh-coalescing.md) includes
full source validation, retained Android identity/message/renewal proof and phone83
installation. Startup request volume, latency p95 and Apple SDK6 runtime remain open.
Existing SDK1–4 releases and retained SDK5 candidate binaries are unchanged.
The following sections preserve historical SDK1–3 acceptance.

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
