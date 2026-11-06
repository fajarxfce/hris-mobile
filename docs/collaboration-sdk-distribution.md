# Android Matrix SDK distribution

The Android recovery adapter needs two additions to the Matrix Rust SDK: reading
an original event without losing fields, and settling a proven server-confirmed
original in a paused local send queue. The caller must still prove the accepted
content and owner; the SDK does not authenticate an arbitrary supplied event ID.
See [recovery contract](collaboration-confirmed-queue-recovery.md).

The verified four-ABI candidate is `dev.fajar.hris.matrix:sdk-android:26.10.23-hris.1`.
It is prepared for a versioned GitHub release in this repository with an Ivy
descriptor; it has not been published at this checkpoint. The application still
uses a temporary local override while the shipping repository is prepared.

The [build manifest](../tool/collaboration-sdk-patches/android-build.json) pins the
upstream revision, both patches, toolchains and generated Kotlin hashes. The
[distribution manifest](../tool/collaboration-sdk-patches/android-distribution.json)
pins the Kotlin wrapper and upstream dependency POM. Follow the
[packaging instructions](../tool/collaboration-sdk-patches/README.md#four-abi-candidate-packaging)
to produce the AAR, Ivy descriptor, source bundle, license, build record and checksums.
Each new binary requires a new version; never replace an existing release asset.
Gradle must verify both descriptor and AAR hashes before accepting the dependency.

The descriptor preserves upstream dependencies, including JNA's Android AAR.
The Kotlin class archive and arm64/x86_64 libraries exactly match the Android
consumer that passed both native recovery scenarios. All four ABIs pass structural,
entry-point and alignment checks. The 16 KiB requirement applies to the two 64-bit
ABIs; the NDK 28 ARMv7/x86 outputs use 4 KiB alignment. These checks do not claim
execution on a physical 32-bit device or R8 compatibility. Existing application
release variants do not enable R8. Apple's dependency remains unchanged.

[SDK measurements](measurements/collaboration-sdk-confirmed-queue-android-2026-11-06.json)
retain the first remote verifier failure separately from corrected local validation.
[Full candidate checks](measurements/collaboration-confirmed-queue-full-candidate-2026-11-06.json)
record 7,026 passing application tests and lint without errors or warnings. Normal
shipping dependency resolution and the application release are separate remaining gates.
