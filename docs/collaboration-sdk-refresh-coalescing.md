# Concurrent SDK credential renewal

Build80 retained its encrypted identity and three original messages, but its first
refresh200 was followed210ms later by another refresh using the just-issued token.
The second request received429. Later scheduled renewals were successful; no reset,
bootstrap or unauthorized disclosure was needed. This does not explain every cold
recovery request; the separate shared background-acquisition gate is under test.

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

A new SDK version still requires verified ABI binaries, the matching generated
bindings, packaging and retained-device acceptance. SDK4 publication and SDK5
binaries must not be overwritten. No candidate binary or phone installation is
claimed by this source change.
