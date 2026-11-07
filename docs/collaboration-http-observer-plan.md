# Matrix HTTP observation — next SDK source increment

Status: design and isolated source baseline only. No HTTP observer, SDK5 artifact,
new generated binding, consumer integration or publication is implemented here.
The shipping SDK4 remains immutable and the application still depends on it.

## Observed source boundary

SDK4 uses Matrix Rust SDK8de215a0 plus the five patches in
`tool/collaboration-sdk-patches/android-existing-identity-build.json`. A fresh
isolated checkout reconstructed all19 recorded patched source hashes. The existing
SDK4 inventory and original Bima private stores were not modified.

`crates/matrix-sdk/src/http_client/native.rs::send_request_inner` runs for each
SDK retry and calls `execute_request`. The latter executes reqwest and converts its
response to the existing buffered HTTP response. The current tracing span aggregates
status/size/duration but exposes no typed FFI HTTP observer. Parsing Android logs or
calling a Ktor plugin would not establish Matrix transport capture.

Instrument the actual execution boundary, preserving existing serialization, timeout,
retry/backoff, authentication, TLS, response bytes, errors and cancellation. Inventory
other direct reqwest paths before declaring coverage; an SDK send attempt is distinct
from hidden engine redirect/socket work. Verify captured requests against server-side
counts in the real fixture. Do not claim coverage of uninstrumented calls.

## Proposed ownership and API

Add a client-owned optional observation registry shared by HttpClient clones. It is
absent by default. A typed subscription exposes a bounded async batch reader and an
explicit close operation. Use a bounded nonblocking producer channel; transport never
waits for a UI callback or a consumer. Avoid an arbitrary foreign callback on the HTTP
path. A single owning reader pulls bounded batches through UniFFI on Android/Apple.
Closing wakes a pending read, stops future ingress and releases the registration;
canceling the host collection closes that exact native subscription.

Each SDK attempt has a stable observation ID independent of Matrix transaction IDs,
a start timestamp, monotonic elapsed time, source/attempt number and observed terminal
state. Keep response-header timing distinct from full-body completion and queue wait.
Observed HTTP status survives later parsing/body failure; a dropped future is canceled,
not successful. Never create message acknowledgements from these diagnostic events.

Bound retained events, batch size, names, paths, headers and every allocation before
crossing FFI. Strip query values, URL credentials, Authorization/cookies, authentication
bodies, plaintext message bodies and private keys before buffering. Start with metadata
only; unsupported body/engine information remains unavailable. Keep a monotonically
fenced subscription generation so old completions cannot enter a replacement recorder.
Overflow is counted explicitly; missing starts/finishes remain incomplete capture,
not fabricated timing/status. Bounded source types are transport metadata, without
HRIS company/account/business state.

## Reusable consumer boundary

The standalone `kmp-network-inspector` repository owns its core record store, Ktor
adapter and future Compose viewer. The real SDK binding adapter translates these
native observations into the reusable record API. It must preserve native observed
timestamps/duration across buffering; current core timing begins at ingestion, so an
explicit external-timing API needs its own validation before that bridge is accepted.
Ktor and Matrix sources remain distinguishable. The host owns subscription/store
lifetime and clears old diagnostics on account replacement. No SDK I/O in a ViewModel.

The initial library core/Ktor targets are Android, iOS, JVM and Wasm. Actual Matrix
transport coverage needs target-specific integration, not just declared common types.
Android JNI and Apple bindings must come from the same newly versioned Rust source.
Do not claim that an Android AAR establishes Apple or browser Matrix acceptance.
The user's physical iPhone test waiver does not waive source/Apple CI verification.

## Required evidence before release/integration

1. Rust unit/real local HTTP tests: each retry, actual status, cancellation before and
   during body transfer, late close/replacement, concurrent attempts, overflow and
   oversized/malformed sensitive metadata. An unconsumed subscription cannot stall HTTP.
2. Paired FFI and generated bindings from frozen source; Android ABI/16KiB checks and
   Apple compilation/runtime checks. A new immutable SDK version and checksums are
   mandatory. Never rebuild or relabel SDK4 assets.
3. Common adapter tests: foreign/late IDs, bounded ingress, clear/disposal, accurate
   native timing, dropped events and response-versus-body failure semantics.
4. Real HRIS dev integration: compare a retained-original send against the server,
   show Ktor and Matrix traffic in the viewer, measure capture overhead and verify
   that message state/ordering/keys are unaffected with capture disabled or saturated.
5. Validated phone update, then reusable host integration in Compose Fluent Starter.
   Publishing the private inspector repository requires the pending user visibility
   choice; elapsed time does not grant that approval.

Continue Mobile95e35b9d/build55 device acceptance independently of this SDK increment.
The original Room-dispatch/communications PRD remains active alongside these repairs.
