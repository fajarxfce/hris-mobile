# Incoming SDK bindings

The accepted sync/key source changes now generate Kotlin and Swift APIs from a host
library built with Android's exact native feature selection. The published Android dependency is
now `26.10.23-hris.8`; [native acceptance](collaboration-sdk-incoming-hints-native.md) verifies the
new callbacks and the previous delivery controls. The owning application hint consumer remains next.

`Client.storedSyncPositionV2` reads the existing SDK position. `SyncResponseV2` carries
its own previous/next positions and bounded timeline changes. Each room change retains
membership, event IDs and a gap flag; the batch separately reports inventory loss.
`Encryption.subscribeToRoomKeyChanges` returns an optional owned `TaskHandle` and
delivers bounded room IDs with a reconciliation flag. It exports no key material.
The application still has to own cancellation, late-callback fencing and durable work.

The host build passes against all 1,221 accepted source hashes. UniFFI 0.32.1 generated
nine Kotlin files and 27 Swift/header/module-map files. Eight Kotlin dependency files
are byte-identical to SDK6; the main binding preserves the existing declarations and
adds the new methods, callback and records. This is generation evidence, not an Apple
compile or native Android acceptance.

`android-incoming-hints-build.json` pins the nine patches, 40 changed source hashes and
the generated Kotlin pair. The existing candidate workflow can build it for all four
Android ABIs. Each target must reproduce every binding hash, export the exact native
methods and meet its ELF alignment requirement before packaging. Packaging must also
verify the compiled JVM API, including the original HTTP observer and queue recovery.
A mismatch rejects the candidate; a different target's generated output must never
silently replace the pins. Published artifacts and retired SDK7 remain immutable.

The five build-driver process/environment/native-symbol tests pass. Full source tests
are recorded in the [sync](collaboration-sdk-incoming-hints.md) and
[key](collaboration-sdk-key-hints.md) contracts. Exact generation inputs and output
hashes: [binding evidence](measurements/collaboration-sdk-incoming-hints-bindings-2026-11-11.json).
Four Android ABI builds are now accepted from immutable b94f6fa79fc1e22b1ded081af03237603fca6f65,
[run 42928136481](https://github.com/fajarxfce/hris-mobile/actions/runs/42928136481). All nine
binding hashes agree across targets; archive digests/CRC, exact source hashes, native entry
points and required ELF alignment were independently checked after download.
See [Android evidence](measurements/collaboration-sdk-incoming-hints-android-2026-11-11.json).
SDK8 packaging now passes (118.225s). The 186,095,557-byte AAR preserves all four native hashes,
consumer rules and runtime dependencies; compiled JVM APIs include the new sync/key methods
and prior observer/queue recovery. An isolated application checkout is testing the candidate.
The isolated application build, six Android native scenarios and publication are now accepted;
see the native contract above. Apple native SDK8 compilation and application hint ownership remain separate work.
