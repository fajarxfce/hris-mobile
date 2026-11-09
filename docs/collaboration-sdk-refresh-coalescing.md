# Concurrent SDK credential renewal

Build80 retained its encrypted identity and three original messages, but its first
refresh200 was followed210ms later by another refresh using the just-issued token.
The second request received429. Later scheduled renewals were successful; no reset,
bootstrap or unauthorized disclosure was needed. This does not explain every cold
recovery request; the separate [background-acquisition fix](collaboration-background-runtime-contention.md)
passes its source and browser tests; combined native acceptance remains open.

The real Matrix HTTP regression reproduces a delayed old-token401 after another
caller has completed renewal. The previous SDK refreshed again and failed the
otherwise healthy request with429. The fixed SDK uses the current token for its
existing bounded request retry. `send_inner` already marks only the exact rejected
access token as expired. Matrix/OAuth inspect that state under their existing
rotation lock. Explicit public refresh calls still rotate, and invalid current
tokens, rejected sessions, cancellation and transient failures keep existing rules.
No ViewModel, HRIS authorization policy or inspector capture behavior changes.

Twenty HTTP refresh tests pass, including both Matrix and OAuth delayed responses,
concurrent success/failure, explicit refresh, missing tokens, authentic rejection
and rate-limit/timeout preservation. FFI and wasm32 compilation pass. The seven
pinned patches reconstruct all1218 source files exactly; five build-driver tests
pass. [Exact evidence](measurements/collaboration-sdk-refresh-coalescing-2026-11-09.json).

`tool/collaboration-sdk-patches/android-refresh-coalescing-build.json` extends the
SDK5 observer manifest with the separate `refresh-coalescing.patch`. The verified
observer manifest/patch and exact-symbol build checks are adopted from6866251a.
The reusable inspector remains observation-only. The candidate workflow accepts
an explicit manifest and ABI; its historical default remains reproducible.

The original four-ABI [CI run](https://github.com/fajarxfce/hris-mobile/actions/runs/42561531330)
passed on dc6ed11e without reruns. All archive digests, nine generated Kotlin files,
source manifests, native symbols and ELF alignment were verified before packaging.
The pinned wrapper produced `26.10.23-hris.6` in 111.937 seconds; its AAR SHA-256 is
`a4aa1e2cb3136c0531bb35f7f06df10582c84c1974ce4e5fda7099d0ab533bb3`.
The application now pins this candidate and its descriptor with strict checksums.

Final full SDK6 application validation passes 5,837 fresh tests, including 595
Android host and 160 actual browser cases, plus unchanged identity desktop coverage.
[Parent renewal evidence](measurements/collaboration-parent-session-renewal-2026-11-09.json)
records source/build/quality gates. Retained82→83 preserves four original messages,
adds exactly one new encrypted message with the receiver stopped, and completes
three successive renewals. Periodic work is attributed separately from the original
strict zero-metadata failure; startup request volume and latency p95 remain open.

[SDK6 release](https://github.com/fajarxfce/hris-mobile/releases/tag/matrix-sdk-26.10.23-hris.6)
438835525 publishes six exact verified assets. All remote sizes/digests match;
the published descriptor was fetched without authentication and verified. Draft
lookup and stale draft asset-URL failures were recovered using the exact release ID
and refreshed published URLs, without overwriting assets or republishing. Previous
SDK releases remain unchanged. Build83 is installed on the authorized Android phone
with exact APK checksum verification and no phone UI interaction. Apple SDK6 runtime
and broader communications acceptance remain open.
