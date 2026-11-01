# Native application-worker recovery

The separate debug-only `apps/collaboration-proof` application now depends on the real
shared application container. `HrisWorkerProofContainer` resolves the same registered
`CollaborationDeliveryWorker` used by the product. It uses Android SQLite, protected
content keys, encrypted command/history/receipt repositories, the actual Matrix SDK and
real HRIS HTTP admission. Only login/workspace inputs are seeded fictional fixtures;
its scheduler records the task key without executing it.

## Process boundary and evidence

The stage process initializes protected sender/recipient SDK records, acquires the
conversation summary through HRIS and queues exact text through `QueueCollaborationText`.
The driver confirms the acknowledged Queued record and live PID, then force-stops only
the proof package on the dedicated emulator. No graceful teardown substitutes for death.

The second process reopens the encrypted application stores and verifies the original
UUID, exact body and Queued state. It confirms that the sender runtime is still closed.
After server admission is enabled, the actual worker restores the sender, stages and
delivers the message, acquires original history and retires the command with its exact
completion evidence. The recipient SDK decrypts the original content and logical ID.
A repeated worker invocation completes without another encrypted send.

`NativeCollaborationWorkerInteropTest` independently checks the gateway trace: no early
send, exactly one encrypted room event, device/conversation/room admission before send,
zero application admission failures and two device-key publications. Reading the stored
provider event confirms ciphertext and the expected sender. The legitimate summary read
has its own trace counter; it cannot substitute for direct-conversation admission.

Private fixture files contain disposable credentials and are removed during cleanup.
Checkpoints export bounded PID/UUID/state/task-key/body-digest evidence; diagnostics
retain only classified failures and bounded source frames. The native driver rejects
changed identity, replayed PID, malformed flags, missing evidence and oversized output.

## Measured scope

The successful worker run used two Android processes, one forced process death, one
worker pass and exactly one encrypted send. The existing native sender/recovery proof
also passed unchanged: four processes, three deaths, three encrypted sends and no send
to the held room. Both used proof APK `924cdd452304635263a410ae2396ae9edbba14a8eda6cef1003555585ad1ceee`
on `hris-mobile-dev`, API 35. The new run passed in 70 seconds; the regression in 78.
The first worker run delivered successfully but failed a fixture assertion because its
summary GET was not recognized by the application trace; that classification was fixed
without weakening the admission checks. Compile/manifest corrections are also retained
in [the measurement](measurements/chat-native-worker-2026-11-01.json).

This accepts the real application worker/Android store/SDK/server composition after
process death. WorkManager did not execute this scenario: Android scheduling, constraints,
retry/cancellation and OS-driven process startup still require separate evidence.
Force-stop itself prevents Android background execution until the application is started
again. No phone UI, live provider, Apple runtime, scale or full PRD group is accepted here.

## Reproduce and continue

Build `:apps:collaboration-proof:assembleDebug`. Start the owned `hris-mobile-dev` emulator
at `emulator-5560`, then run the Platform server test with `HRIS_COLLAB_NATIVE_APK` pointing
to that APK and Docker access. Run `*NativeCollaborationWorkerInteropTest` and
`*NativeCollaborationSdkInteropTest` separately; each owns its disposable provider/HRIS
fixtures and cleans the proof package. Never target the user's phone with this driver.

Continue real WorkManager lifecycle evidence, then remote history, durable drafts and
safe pending cancellation, incoming FCM/inline replies, groups/media/actions, key
continuity/backups, Apple/web adapters, calls and meetings. Keep each accepted boundary
separate from the remaining product acceptance criteria.

The native HRIS CI workflow pins Platform `784fc9113a4f09fd1ed9bff7541479088263aca5`, executes both scenarios
on the owned emulator and uploads their bounded result/checkpoint/trace/XML evidence.
Changes in apps.shared now trigger the workflow. Local native results above remain
separate from hosted acceptance; audit the matching commit's run/artifacts after push.
