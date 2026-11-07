# Matrix HTTP observation — next SDK source increment

Status: observer source increment locally accepted. Core15 tests, native FFI and
Wasm core compilation pass. No generated binding, SDK5 artifact, Android/Apple ABI
acceptance, application integration or publication yet. Shipping SDK4 is unchanged.
[Exact checks](measurements/matrix-http-observer-source-2026-11-07.json).

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

## Implemented source increment — 7 November2026

`tool/collaboration-sdk-patches/http-observer.patch` applies after the five SDK4
patches. Its separate `http-observer-source.json` pins the base manifest and10 changed/
new files; the shipping SDK4 manifest is not edited. The patch was reconstructed in
an isolated repository and every resulting file hash matched tested source.

Client::observe_http_requests returns an explicitly owned subscription. Producers use
try-lock admission and never await a reader. The256-event buffer reports overflow and
contention, returning at most32 records per batch. Close, replacement, client registry
destruction and subscription drop clear the old queue and wake readers. In-flight
attempts keep their original queue so they cannot enter a replacement capture. An
unconsumed channel cannot delay a request. No foreign callback executes on the HTTP path.

Started, Response and Finished records carry real native timestamps/monotonic elapsed
time. Response status survives later body failure; canceling an in-flight future emits
cancellation, never success. Request/response bodies and query/header values are never
retained. Path/query/header work is bounded before buffering. No auth, retry, TLS,
request bytes, response bytes or Matrix parsing semantics are changed.

Fifteen cases pass, including real local HTTP401, SDK429→200 retry with exact server
count, interrupted body, cancellation, saturation and lifecycle fencing. FFI source
and wasm32-unknown-unknown/indexeddb compile. This does not establish Apple/native ABI,
generated Kotlin/Swift, browser runtime, live HRIS capture or overhead acceptance.
The standalone viewer is locally accepted at inspector f71f308; its repository stays
private while the visibility question is pending. It still needs the external-timing
API and Matrix adapter before app integration.

Coverage deliberately excludes OAuth's separate client, QR authentication HTTP,
raw reqwest access (including the external issuer request in FFI), and redirects
inside reqwest. Source supports observing native SDK attempts, not every socket.
Keep these exclusions visible until separately instrumented and tested. No SDK5
version or assets may be published by reusing SDK4's version or by substituting
an operation log for actual HTTP observation.
