# Incoming SDK binding candidate

The accepted sync/key source changes now generate Kotlin and Swift APIs from a host
library built with Android's exact native feature selection. No application dependency
has changed. Shipping remains `26.10.23-hris.6`.

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
Android builds, packaging, Apple compilation, application consumers and actual incoming
event/key recovery remain separate acceptance work.
