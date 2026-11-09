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

All 592 Android host tests pass with SDK6; release/proof compilation, Android lint
and format/architecture/detekt checks pass in 492.999 seconds with unchanged inputs.
The preceding 4,261-test full run used SDK5. Only the catalog and checksum metadata
changed between these gates; unchanged common/browser code retains that evidence.
The full suite was not repeated on SDK6. Retained-device checks remain pending. SDK6 is available
only in the local candidate repository at this checkpoint. SDK4 publication and
SDK5 binaries are preserved. No phone installation or latency acceptance is claimed.
