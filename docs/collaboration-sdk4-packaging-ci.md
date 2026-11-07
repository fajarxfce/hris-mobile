# Reproducible SDK4 packaging in the Android proof

The first native run for `05de0bf6` stopped on both ABIs before fixture installation.
Gradle reported that Matrix could not be stripped and was packaged as-is; the driver
then rejected the library digest. The original APK bytes were not uploaded, so their
exact native hashes are unknown. Both sets of 493 host tests passed.

The proof module now selects NDK 28.2.13676358 from the shared version catalog. The
native workflow installs that version before building and runs the existing strict
APK validator before starting the emulator. Its artifact records the APK hash, each
Matrix library hash, selected NDK and validation outcome, including before rejection.
The accepted SDK4 hashes and package/debug/ABI restrictions are unchanged.

The exact published AAR, stripped locally with that NDK, reproduces both previously
accepted ARM64 and x86_64 library hashes. Full root, Android host, proof assembly,
lint and quality checks passed 5,373 fresh cases in 754 suites (1,364.389 seconds),
with 4,324 unchanged inputs and no failures/errors/skips. Proof lint has zero errors
and its 49 existing warnings. Compiler warnings are retained in the recorded logs.

The exact workflow Python step accepts the resulting x86_64 APK. Replacing only its
native library in a separate local copy with the published unstripped bytes reproduces
rejection and retains the differing hash. That copy is a packaging reproduction, not
the unavailable original CI APK. Neither APK was installed for this check. No SDK
rebuild, provider send, product runtime change or Starter port is involved. The first
remote run of the corrected source remains separate acceptance.

[Evidence](measurements/collaboration-sdk4-packaging-ci-2026-11-07.json),
[original failed CI](measurements/collaboration-original-metadata-native-ci-2026-11-07.json).
