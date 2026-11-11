# Native SDK8 incoming hints

The published Android SDK `26.10.23-hris.8` is now the Mobile dependency. Six real native
scenarios pass on a fresh x86_64 emulator, including one new sync/key scenario and all five
previous encrypted delivery, incoming worker and offline history controls. The application
consumer of these hints remains separate work; no phone latency improvement is claimed.

The new scenario borrows the existing fictional peer's SDK. It receives the initial reconciliation
callback, observes an actual room-key import, checks each response's previous/next positions against
the SDK store, retains four event kinds, and cancels and joins the native listener. It uses exactly
the same three sync calls as the old scenario. Counters remain Matrix 32/native 9, including
four encrypted sends, three syncs, two secret-storage reads and two provider pages. It creates
no extra client, transport request or synchronization loop.

Existing worker behavior remains Matrix 31/native 14 with two scheduled turns. Original Company/Space
messages keep their identities after lost acknowledgement and process death, and acquired history
reopens with transport removed. All six cases pass in 290.299s; no compiler ran during the emulator
experiment, and the fresh 2,560 MiB/two-core emulator stopped afterward. Existing employee stores,
keys and the phone were untouched.

The proof APK compiles and passes 21 supporting tests, format, architecture and static checks.
The Group fixture compiles with 8,743 fixed inputs; 42 Python protocol checks pass. The new native
scenario is explicitly opt-in, and the artifact verifier retains SDK6 as its default. SDK8 must be
selected explicitly and match the independently stripped native library hash. Unknown/retired
versions and incomplete callback evidence are rejected.

The four-ABI AAR, descriptor, source bundle, license, build record and checksums are published as
[SDK8](https://github.com/fajarxfce/hris-mobile/releases/tag/matrix-sdk-26.10.23-hris.8), targeting
immutable b94f6fa79fc1e22b1ded081af03237603fca6f65. All six GitHub digests and downloaded file
hashes match. The descriptor is also fetched without authentication through the normal dependency
URL. All 4,864 accepted source inputs match the primary Mobile checkout after adoption. SDK7 stays
retired; earlier published releases remain unchanged. Normal public application dependency
selection also passes in 17.910s, resolving the inspector's SDK6 requirement to SDK8.

The isolated application build already passed 10,901 fresh tests plus 100 exact unchanged browser
database controls. The new two-file native proof then passed its own build and runtime gates.
The first Group formatting command used an invalid task name, changed no source and is retained
privately; the corrected command passes. No product failure was hidden or bypassed.

Remaining work: the owning application handoff must retain incoming work and its cursor atomically,
cover provider inventory loss, resolve unknown physical rooms under admission, route original Company
rooms separately, and resume matching missing-key work without restarting metadata. Apple native SDK8
packaging/execution and actual push/call acceptance remain open. All 54 PRD groups remain open.

Evidence: `collaboration-sdk-incoming-hints-native-2026-11-11.json` in the shared measurements directory.
