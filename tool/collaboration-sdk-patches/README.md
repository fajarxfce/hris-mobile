# Patched Matrix Android SDK

The [SDK8 Apple candidate](../../docs/collaboration-sdk8-apple-candidate.md) reuses the immutable
Android SDK8 source manifest and all nine patches. Its separate `swift-pinned-build.patch`
only makes the upstream Apple builder honor the pinned Rust toolchain and Cargo lockfile.
The Apple manifest/build driver verifies five native architectures and real Swift contracts;
candidate validation is separate from application integration and publication.

## SDK8 incoming hints — 11 November 2026

`sync-incoming-hints.patch` and `stored-key-hints.patch` apply after the seven SDK6 patches.
The latest source-only manifest `stored-key-hints-source.json` pins all nine patches and the
exact reconstruction. Final full FFI passes 88 cases, including 29 new sync/key cases. Native
feature graphs are unchanged by correcting optional search activation. Core WebAssembly passes;
full FFI WebAssembly is still blocked by its existing filesystem logging dependencies.
[Sync contract](../../docs/collaboration-sdk-incoming-hints.md),
[key contract and evidence](../../docs/collaboration-sdk-key-hints.md).
Host Kotlin/Swift generation now passes, and `android-incoming-hints-build.json` pins the
expected native/Kotlin pair for four-ABI validation. Eight of nine Kotlin files are unchanged
from SDK6. Four-ABI packaging and six actual Android native scenarios now pass; see the
[binding contract](../../docs/collaboration-sdk-incoming-hints-bindings.md) and
[native acceptance](../../docs/collaboration-sdk-incoming-hints-native.md).
SDK8 is published and adopted. The owning application hint consumer and Apple native SDK8
remain separate work. Earlier candidate/publication statements below are historical;
[distribution status](../../docs/collaboration-sdk-distribution.md) owns current shipping facts.

## Concurrent-refresh candidate — 9 November 2026

`android-refresh-coalescing-build.json` extends the verified observer source with
`refresh-coalescing.patch`. Matrix and OAuth reuse an already refreshed token when
a delayed old-token rejection arrives, under the existing rotation lock. All20
refresh tests, FFI/Wasm compilation and1218-file clean reconstruction pass. ABI
binaries, packaging as `26.10.23-hris.6`, and592 Android consumer tests now pass;
native acceptance and publication remain pending.
[Contract and evidence](../../docs/collaboration-sdk-refresh-coalescing.md). The
historical publication statements below do not describe this candidate.

The last published prerelease is `26.10.23-hris.4`; the application currently pins
the local SDK6 candidate. See the
[distribution contract](../../docs/collaboration-sdk-distribution.md) and
[ARM64 acceptance](../../docs/measurements/collaboration-sdk-arm64-baseline-2026-11-06.json).
Earlier sections below retain their historical scope and failures; they do not
override the current candidate status above.

## Existing private-account reader candidate

`existing-crypto-identity.patch` applies after the four SDK 3 patches on the same
pinned upstream. It is deliberately not in the shipping build manifest yet. The
read-only crypto-account reader passed cipher11, SQLite6 and FFI1 cases; clean
reconstruction matches all1,214 tested files. [Contract and limits](../../docs/collaboration-sdk-existing-identity.md),
[evidence](../../docs/measurements/collaboration-sdk-existing-identity-2026-11-06.json).
ARM64 and its nine generated Kotlin files are now validated. The separate
`android-existing-identity-build.json` pins all five patches, 19 changed sources and
the new binding pair; pass it through `--manifest` to either build or package driver.
The SDK workflow now builds that candidate. Both drivers retain their SDK 3 default
for explicit historical reproduction; never republish SDK 3. Other ABIs and actual
consumer acceptance remain pending. [Android evidence](../../docs/measurements/collaboration-sdk-existing-identity-android-2026-11-06.json).
SDK 3 assets and the app dependency are unchanged; a changed binary requires a new version.

## Refresh backoff candidate

`refresh-retry.patch` extends the current candidate manifest after the three
SDK 2 patches. The shipping dependency remains SDK 2 until a separately validated
new distribution replaces it; published SDK 1/2 assets are immutable.

The retained Bima emulator reproduced a successful token refresh followed by
`429 / M_LIMIT_EXCEEDED / collaboration_gateway_rotation_too_soon`. The SDK
classified that transient response as a rejected session and also treated any
refresh failure as an expired send-queue token. The patch keeps transient refreshes
retryable, preserves Retry-After, and forwards the wrapped Matrix error through
the existing FFI type so Android's owned sync pump observes the server deadline.
Real authentication/access denials retain their original classification. No public
binding API, credentials, queue identity or ARM64 feature flags change.

Two core regressions failed before the patch and passed after it. All 18 HTTP
refresh integration cases and two FFI mapping cases pass; the three SDK build-driver
tests pass. A clean upstream checkout plus four patches reconstructs all 12 pinned
source files exactly. All five core error-policy cases pass. Native ABI builds, actual HRIS recovery
and candidate/normal release acceptance remain.
[Evidence](../../docs/measurements/collaboration-sdk-refresh-retry-2026-11-06.json).

## Raw-event development history

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
It has not been published; the two-ABI successor below passed isolated consumption.
Before shipping, build the required remaining ABIs and Swift/XCFramework pair and
prove the native HRIS edit exchange
under existing owner/admission checks. Android 26.10.23 and Apple 26.10.27 remain
unchanged. Local history never waits for a point fetch.

## Accepted isolated Android consumer probe

`raw-event-android-consumer.patch` applies to HRIS Mobile
`270efd56d0f8e39a45143954c46210bb4653f833` in an isolated checkout. It extends the
existing emulator-only native provider fixture with two direct calls to the new
binding. Each call checks SDK decryption classification, exact event/sender/time,
the original body and custom logical identity, within the 96,000-byte bound.
The existing provider fixture independently checks encrypted wire content, SDK
credential renewal and durable queue behavior. No raw body or key is added to logs.

The patch compiled and passed the isolated Android/provider fixture on 6 November.
The two-ABI AAR is 96,782,109 bytes, SHA-256
`94d907f139a7484bd687b538d8cfce3e90478daa2ab93b9835d9aa8a606becae`.
Consumer build/lint passed in486.129s (0 errors/48 warnings); the provider proof
passed55.429s with both browser cases, native encrypted exchange and cleanup.
[Consumer evidence](../../docs/measurements/collaboration-sdk-raw-event-consumer-2026-11-06.json)
records setup failures separately. The fixture requires exactly `emulator-5560`;
wait for completed boot before invoking it. Fresh consumer userdata was used and
the owned emulator is stopped. The raw-read assertions execute inside the proof APK;
the standard runner does not retain the additional numeric result marker.

At this November6 checkpoint the patch could not compile against the then-shipping SDK.
Current26.10.23-hris.6 includes the raw API; its immutable AAR was inspected on November10.
See the [current acquisition review](../../docs/collaboration-space-incoming-plan.md) before
using this historical patch. That API inspection is not new native edit-recovery acceptance.
For reproducing this historical candidate, use its paired AAR, isolated local Maven coordinate
and exact artifact/POM verification hashes; keep
shipping dependency verification enabled. Run the existing provider driver with
`--android-sync-mode classic` on the explicitly owned emulator. Passing this
probe alone will not accept HRIS edit recovery, cold-cache completeness, remaining
ABIs, Apple integration or minified application packaging.

The production upstream AAR contains four ABIs: arm64-v8a, armeabi-v7a, x86 and
x86_64. The accepted x86_64/arm64 pair covers the phone
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

## Tested confirmed-queue SDK operations

`confirmed-queue.patch` applies **after** `raw-event.patch` on the same pinned Rust
SDK. Its SHA-256 is
`23354aeb43aabe937543355d7255d31527c0a3c78acf0a677f113e9592776872`.
It reconstructs the four tested source files exactly from the saved raw-event
baseline. [Evidence](../../docs/measurements/collaboration-sdk-confirmed-queue-2026-11-06.json)
records8 core cases and19 FFI room cases passing on the identical final sources,
with no warnings. These are Rust/SQLite tests, not Android binding or app acceptance.

`Room.queued_message_events` captures exact transaction/type/content from the native
store under its queue lock. It rejects enabled/in-flight, upload/redaction or
incomplete dependent work. Local echoes are unsuitable: some native requests are
omitted or synthesized there. The FFI validates unique IDs, JSON objects,128 entries,
96,000 UTF-8 bytes per event and1,048,576 total bytes. The underlying SDK store first
materializes its queue; this is not a bounded SQL-query claim.

`Room.settle_confirmed_queue_event` compares the transaction, type and exact raw
JSON bytes while paused/quiescent. It preserves changed entries and dependent work,
then removes only a proven original and publishes one local SentEvent. It does no
send/cancel/server-redaction request. Repeated settlement returns Missing. The
caller must independently prove the authenticated sender, room, device/native
family, complete content and accepted server event; supplying an event ID alone
is not proof. Do not substitute ordinary abort, which may schedule redaction.

Tests cover concurrent acknowledgement, cancellation while waiting for the store
lock, changed content, preserved native redaction/dependents and SQLite reopen
before/after settlement without a send/redaction request. Diagnostic Debug output
is redacted. The previous raw-event AAR/consumer excludes this increment. The new
arm64/x86_64 candidate is built; generated JNI/Kotlin remain paired.
Application integration is accepted as an isolated candidate below; complete SDK
distribution and minified application acceptance remain outstanding.

## Accepted confirmed-queue application candidate

`confirmed-queue-android-consumer.patch` applies to Mobile
`f6532c82d1b5f491e1c848dee0a6ace276e22e71`. Its SHA-256 is
`a97d23385e7f4a399edd1c0c420c5759d3f6b8dc39eb48b8c6f781bad4b526b7`.
An isolated checkout reconstructed all21 source/verification files exactly.
It contains the domain settlement policy/use case, raw Android queue adapter,
worker orchestration, regression tests and actual native proof scenario. It is
not a shipping dependency change: the new Android methods require the paired SDK.

The new paired AAR has SHA-256
`4534e86ed5f15fa036cf5c198cfbf551dbc742ca68d32363e8ba68305ddbb92a`,
96,849,072 bytes. All nine generated Kotlin files match between arm64 and x86_64;
the APK contains the exact JNI pair. Candidate build/lint passed370.511s with380
host cases and zero lint errors. A fixture-only correction rebuilt80.140s with18
fresh proof cases; its362 unchanged Android data cases retain their earlier pass.
The final debug proof APK SHA-256 is
`fd265937885b5cada09d95afae9e8bf5eb6e6727c65cf839181a50c612cec914`.

Both actual HRIS native scenarios pass171.206s with12,454 source inputs unchanged.
The retained-original scenario survives two process deaths, settles with the gateway
disconnected, and repeats without another mutation. Each scenario sends exactly3
encrypted messages and makes zero server-redaction requests; both clean up.
The test constructs accepted content through a separate SDK sendRaw request while
leaving the queued original paused. Its HTTP transaction differs, so this proves
the recovery postcondition rather than literal lost-response injection. The first
two attempts failed in fixture setup: shared provider login state, then accidentally
using the joining timeline entry offline. Provider isolation and existing local
openTimeline correct those paths without changing settlement assertions.

Use a local Maven coordinate `org.matrix.rustcomponents:sdk-android:26.10.23-hris-confirmed-queue-local`
and the exact owned AAR/POM hashes in the patch for isolated compilation. Preserve
strict dependency verification. The current workspace's candidate init script and
build drivers are recorded in the handoff; source/toolchain instructions above
reconstruct the SDK if those local files are unavailable. Do not install this proof
package on the user's phone. Complete remaining ABIs, SDK distribution and minified
product validation before making this the normal application dependency.

[Consumer/native evidence](../../docs/measurements/collaboration-confirmed-queue-android-consumer-2026-11-06.json)
records APK, JNI, source, patch and test hashes and retains failed attempts separately.
The [domain contract](../../docs/collaboration-confirmed-queue-recovery.md) describes
ownership, complete-content matching and protected uncertain queue entries.

## Pinned SDK build driver

`../build_collaboration_sdk.py` takes a fresh checkout of the pinned upstream commit,
an explicit ABI, an Android SDK directory, and separate tooling/output directories.
`android-build.json` pins all three patches, nine relevant Rust/build inputs, the
toolchain versions and every generated Kotlin file. The driver rejects changed
inputs, checks ELF architecture, ABI-specific alignment and the three required entry points,
and writes candidate evidence beside the JNI/Kotlin pair. It never publishes an
artifact or changes an application dependency. Timeout handling reaps its owned
subprocess; three tests cover process lifetime, exit codes and rejected ambient Rust flags.

`.github/workflows/collaboration-sdk.yml` builds arm64-v8a on changes to these SDK build
inputs; manual runs can select another ABI or all four. It uses an isolated runner,
read-only repository permissions and a seven-day candidate artifact. Downloaded
artifacts must pass source, Kotlin, native and workflow-artifact digest checks before
being combined with the already accepted pair. The first hosted x86 build completed compilation but failed the old ELF32 alignment
assertion. Its artifact passes the corrected local verifier; the corrected hosted
run is tracked in the Android evidence. A syntax/process test alone does not accept JNI.

Android's [16 KB page-size requirement](https://developer.android.com/guide/practices/page-sizes)
applies to `arm64-v8a` and `x86_64`. The verifier requires 16 KiB LOAD alignment for
those ELF64 binaries and 4 KiB for ELF32 ARMv7/x86, matching NDK 28 output. It rejects
wrong architectures and missing queue/raw-event entry points independently.

## Four-ABI candidate packaging

`../package_collaboration_sdk.py` uses the pinned Kotlin wrapper revision in
`android-distribution.json`. Populate its Android `src/main` from the four verified
native outputs; every ABI must share the exact nine Kotlin files in `android-build.json`.
Keep the patched SDK checkout available for source fingerprints, license and lockfile.
Choose an unused revision for a new distribution; SDK 2 is already immutable.

```sh
python3 tool/package_collaboration_sdk.py \
  --wrapper /path/to/matrix-rust-components-kotlin \
  --sdk /path/to/patched-matrix-rust-sdk \
  --android-sdk /path/to/android-sdk \
  --java-home /path/to/jdk-21 \
  --version 26.10.23-hris.3 \
  --output /path/to/new-candidate-directory
```

The command checks all four native architectures, alignment, exported methods,
generated Kotlin and pinned sources before packaging. It then verifies the AAR's
JNI hashes, public methods, minimum SDK and consumer rules. The Ivy descriptor
preserves the checksum-pinned upstream runtime dependencies, including JNA's Android
AAR. The source bundle includes generated Kotlin, all patches, manifests, license
and Cargo lockfile; the upstream and wrapper revisions are recorded in `sdk-build.json`.
The output also includes `SHA256SUMS`. The command never publishes a release or
changes application dependencies. SDK 2 packaging passed in 107.344s. Its ARM64
library passed retained-store and native encrypted recovery acceptance before
publication. The three other ABI libraries are unchanged from SDK 1, with source
and effective target-configuration equivalence recorded. All generated Kotlin and
compiled binding classes remain unchanged; see the ARM64 measurement for hashes.

A shipping artifact must pass normal Gradle resolution with strict descriptor/AAR
hashes and the application release checks. Publish only those exact immutable files;
do not replace an existing version or confuse a failed CI run with independent
local verification. Existing application release variants do not enable R8, so their
acceptance is not evidence of minifier compatibility.

## Android ARM64 CPU baseline — accepted

`android-arm64-baseline.patch` applies after the two recovery patches. It changes
only the Android arm64 target configuration: NEON remains enabled, while AES, SHA2
and PMUv3 are no longer unconditionally enabled. Crypto crates keep their runtime
feature detection and software fallback. The pinned source/patch hashes include
this configuration change. Ambient Rust flags are rejected by the build driver.

Release 44 crashed with SIGILL while importing an existing encrypted SDK store
under ARM64 translation, despite successful ELF/API checks. The failed APK and
SDK 1 assets remain intact. Candidate 45, using SDK 2, reopened the same retained
store twice offline and twice online and remained alive afterward. Both screenshots
were inspected. Two additional ARM64 native scenarios passed encrypted messaging,
process restarts, offline confirmed-original settlement and repeated no-op recovery.

The SDK workflow defaults to arm64-v8a. The HRIS native workflow has x86_64 and
ARM64 jobs and asserts each APK's exact native ABI before execution, so the ARM64
bridge run cannot silently use x86_64. Structural checks and source CI remain
separate from actual native runtime acceptance.
