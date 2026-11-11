# Raw Matrix sync batches

The Android sync adapter now retains each SDK8 response's own previous/next position,
Joined/Left room changes, event locators, limited flags and explicit inventory-recovery
signal. Previously the adapter discarded this response after confirming first sync.
The mapper detaches native mutable lists; it exports no message content.

The existing `SdkSyncPump` owns the one request lane. Observation does not start a loop
or acquire another client. First-sync readiness is published after the successful
batch. A failed request retains the last successful batch; stop drains a pending
request and suppresses its late success. Existing terminal-denial precedence, retry
limits, long-poll settings and shutdown ownership are preserved.

`CollaborationSdkSyncConnection.batches` is a conflated, process-local `StateFlow`.
A late observer receives the latest response; it does not receive a durable replay.
Its consumer must compare its saved position with that response's own previous/next
pair. It must retain changed work and the new position atomically, with explicit
provider-inventory recovery for a missing interval. Reading the SDK's current position
later cannot establish the identity of an earlier response.

## Validation

118 focused cases pass: 32 JVM, 54 Android host and 32 Chromium/Wasm. Ten new cases
cover detached native values, positions and gaps, passive observation, failed requests,
late responses, cancelled stop waiters, conflation and redacted diagnostics. Existing
first-sync, lifecycle and runtime-handover controls remain. The first private browser
filter selected no cases; its corrected run preserves the original JVM/Android evidence.

The complete application gate passes 11,023 cases: 3,819 fresh and 7,204 exact accepted
unchanged controls, zero failures/errors/skips. The 293 controls with different report
hashes from the isolated baseline match the accepted foreground archives exactly;
their source/dependency inputs and non-Matrix catalog entries are unchanged. Format,
architecture, detekt, Android release compilation/lint, browser checks, Wasm compilation
and iOS metadata compilation pass. This does not claim an Apple native SDK8 build.

Six actual SDK8 native scenarios pass in 288.308 seconds on a fresh 2,560 MiB/two-core
emulator. They preserve encrypted reads, edits/redactions, pending-key recovery,
automatic incoming work, lost original acknowledgements, process death and offline
reopen. Request counts stay at Matrix 32/native 9 for the sync/key scenario and
Matrix 31/native 14 for the automatic worker. No compiler ran during native execution;
the emulator was stopped afterward. The user's phone was not used.

The generated debug APK initially retained an unused incremental ZIP gap. Repackaging
only that generated output removed 113,527,072 bytes, preserving all 633 ZIP entries
byte-for-byte and all source/dependency inputs. The compact 148,551,112-byte proof APK
was used for native acceptance; the original package and failed audit checks remain
in the private evidence. No application-code workaround or test rerun was needed.

This raw boundary does not yet subscribe the application to changes, retain a durable
position, resolve unknown rooms, consume stored-key callbacks or implement incoming
push routing. No user-phone latency result or closed PRD group is claimed. SDK8's
previous native proof is described in [SDK incoming hints](collaboration-sdk-incoming-hints-native.md).

[Exact validation evidence](measurements/collaboration-raw-sync-batches-2026-11-11.json).
