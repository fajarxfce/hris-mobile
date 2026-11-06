# Matrix raw-event binding under validation

`raw-event.patch` applies to Matrix Rust SDK commit
`8de215a0aa0026afa5754ae5f05a6d168d1a1d60` (Apache-2.0).
It is an isolated SDK-source increment, not a shipping dependency change.

The FFI point reader preserves exact JSON after the existing SDK decryption attempt,
returns the SDK decryption classification, checks the requested event ID, and caps
UTF-8 output at96,000 bytes. A point fetch does not write the event cache: sync owns
that persistence. A successful decryption does not assert sender verification.

[Validation evidence](../../docs/measurements/collaboration-sdk-raw-event-tests-2026-11-06.json)
records nine passing Rust tests on locked dependencies with Rust1.96. Tests cover
unknown original/edit/reply/forward metadata, encrypted reads, missing keys, SQLite
reopen without another event fetch, cancellation, identity and byte bounds.

Android arm64 JNI and nine Kotlin binding files have now been generated from the
same binary. [Build evidence](../../docs/measurements/collaboration-sdk-raw-event-android-2026-11-06.json)
records source/output checksums, Rust 1.96.0, cargo-ndk 4.1.2, NDK 28.2.13676358,
API 21 and four ELF LOAD segments aligned to 16 KB. The first run compiled the
library but could not generate bindings because offline cargo metadata lacked an
all-target dependency. A locked metadata fetch resolved this; the second run reused
the identical binary and completed generation in 1.917 seconds.

For a fresh build, fetch locked dependencies for **all** targets before going offline;
UniFFI generation calls cargo metadata without an Android target filter. Apply the
patch to the pinned SDK, then run its host `xtask kotlin build-android-library
--package full-sdk --release --only-target aarch64-linux-android --src-dir OUTPUT`
with the toolchains above, `CARGO_NDK_PLATFORM=21` and `CARGO_NET_OFFLINE=true`.
Keep generated Kotlin and JNI together; never combine them with another SDK build.

These files are not yet an assembled or consumed AAR. Before consumption, compile
the bindings in the pinned upstream Kotlin wrapper, build the required remaining
ABIs and Swift/XCFramework pair, and prove the native HRIS edit exchange under
existing owner/admission checks. Android 26.10.23 and Apple 26.10.27 remain unchanged.
Local history never waits for a point fetch.
