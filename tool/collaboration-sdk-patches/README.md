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

The paired arm64 files now compile into an AAR in 164.980 seconds using Kotlin
wrapper `c1664f712bed33488b10f33232fb424faf257d49`, Gradle 8.14.2, Java 21 and the
same NDK. Replace its entire generated Kotlin/JNI pair, then run only
`:sdk:sdk-android:assembleRelease`; no upstream publication task is needed. The
wrapper build configuration required no changes. The AAR JNI bytes match the
generated binary, and compiled Room/RoomInterface expose `loadOrFetchRawEvent`
with both raw event result types. Upstream consumer rules and the packaged file
are both empty; this is not minified-consumer acceptance.

The 50,667,076-byte arm64 candidate has SHA-256
`691c96bb9a502c8ab5a684259a753bc8017fce61c66cfef2e4dd7963bc02da43`.
It has not been published or consumed. Before consumption, build the required
remaining ABIs and Swift/XCFramework pair and prove the native HRIS edit exchange
under existing owner/admission checks. Android 26.10.23 and Apple 26.10.27 remain
unchanged. Local history never waits for a point fetch.

## Prepared Android consumer probe

`raw-event-android-consumer.patch` applies to HRIS Mobile
`270efd56d0f8e39a45143954c46210bb4653f833` in an isolated checkout. It extends the
existing emulator-only native provider fixture with two direct calls to the new
binding. Each call checks SDK decryption classification, exact event/sender/time,
the original body and custom logical identity, within the 96,000-byte bound.
The existing provider fixture independently checks encrypted wire content, SDK
credential renewal and durable queue behavior. No raw body or key is added to logs.

The patch is prepared and formatted, **not compiled or executed yet**. It cannot
compile against the shipping SDK. Use only the paired candidate AAR, with an
isolated local Maven coordinate and exact artifact/POM verification hashes; keep
shipping dependency verification enabled. Run the existing provider driver with
`--android-sync-mode classic` on the explicitly owned emulator. Passing this
probe alone will not accept HRIS edit recovery, cold-cache completeness, remaining
ABIs, Apple integration or minified application packaging.

The production upstream AAR contains four ABIs: arm64-v8a, armeabi-v7a, x86 and
x86_64. The in-progress x86_64 build and existing arm64 candidate cover the phone
and emulator proof targets; both 32-bit targets are still required before replacing
the complete shipping artifact. Keep generated Kotlin/JNI pairs together for every
ABI and compare all nine generated Kotlin files before combining native libraries.

## Confirmed native queue recovery regression

`confirmed-queue-repro.patch` applies to Mobile46bec39a (and docs-only56f48bd8).
It is a diagnostic expected-failure test, separate from the raw-event binding patch.
The application worker uses real encrypted command/history stores and a fake native
SDK that preserves its transaction after accepting an event and losing the response.
Recovery completes the first message, then the next message is blocked with
`client.collaboration_queue_unresolved`. The original fixture removed the native
transaction before returning its lost-response failure and could not expose this.

The focused case ran twice:42.659s, then40.847s with phase-specific assertions.
Both produce the expected single failure; the second names delivery of the next
message as the failing step. [Evidence](../../docs/measurements/collaboration-confirmed-queue-reproduction-2026-11-06.json).
The tested patch is retained here; baseline test files were restored afterwards.
No production source changed, and no native or phone reproduction is claimed.

To reproduce on an isolated checkout, apply the patch and run
`:apps:shared:desktopTest --tests '*CollaborationDeliveryWorkerTest.recoveredNativeAcceptanceDoesNotLeaveAnUnmappedQueueBlockingTheNextMessage'`
with the repository's Java21/headless setup. Expect a red test until confirmed-queue
settlement is implemented. Do not automatically abort native handles: the pinned
SDK can queue a server redaction when their send is in flight. Exact original
transaction evidence and a settlement operation with no redaction side effect are
needed before removing a retained native entry.
