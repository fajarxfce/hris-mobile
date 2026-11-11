# Isolated SDK8 application validation

The Android candidate `26.10.23-hris.8` passes the isolated application build and checks.
The shipping application still uses SDK6; native callback acceptance and publication remain pending.

Only the SDK version, verified artifact metadata and the Android test fixture's new sync response
fields differ from the accepted application source. All 4,863 inputs stayed fixed during the run,
and the shared network inspector stayed unchanged. Full checks, Android host tests, release Kotlin
compilation/lint, shared Wasm/iOS metadata compilation, collaboration browser tests and the protocol
APK build completed in 1,024.659 seconds.

10,901 tests ran with no failures/errors/skips. All previous case identities were preserved;
100 independent browser database controls are reused from exact source/build/dependency inputs.
The fresh checkout also executed 19 existing presentation/datastore/build-logic cases not included
in the earlier aggregate. Total evidence: 11,001. These are not 19 newly implemented tests.

The protocol APK contains the independently stripped SDK8 x86_64 library. Native source and AAR
hashes remain pinned to the accepted four-ABI build. Actual native key notification, exact sync
position and cancellation tests are the next gate; this build alone proves no runtime improvement.

The first build stopped before tests because the isolated Ivy repository lacked the inspector's
unchanged SDK6 descriptor. Adding the exact verified published SDK6 files fixed repository
resolution without source changes. Initial audit also found browser database reports absent in
this fresh checkout; the final audit explicitly verifies their archived controls instead of
claiming they ran here. Both attempts are retained privately.

See the shared measurement file `collaboration-sdk-incoming-hints-consumer-2026-11-11.json`.
The next incoming consumer must retain cursor/work together, preserve separate provider inventory
recovery and respect the encrypted demand document's 65,536-byte plaintext limit. Raw SDK event
batches must not simply be copied into that bounded document. No native callback is wired into
application recovery by this checkpoint.
