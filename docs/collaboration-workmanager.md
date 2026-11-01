# Android-scheduled encrypted delivery

The dedicated proof now exercises the real `AndroidWorkScheduler`, `SyncWorkerFactory`,
`SyncWorker` and registered application delivery task with protected Android storage,
the Matrix SDK and the HRIS/Synapse gateway. Login/workspace inputs remain fictional
fixtures; this does not substitute for production authentication acceptance.

## Actual lifecycle

1. A normal proof Activity prepares the protected sender/peer and conversation while
   online. The application container is created after the fixture's owner renewal.
2. The driver disables only the owned emulator's connectivity. Queueing uses the product
   use case and scheduler. Read-only inspection verifies an ENQUEUED WorkSpec, CONNECTED
   constraint, the production worker class, zero initial attempts and only `sync_task`
   in input data. Message content stays in the encrypted application command store.
3. The Activity moves behind the launcher. The driver confirms it is no longer resumed,
   sends SIGKILL to that exact owned PID and verifies that the package is not force-stopped.
   Its constrained system job remains registered. No instrumentation process is used for
   this death, and no manual `jobscheduler run` command starts delivery.
4. After server admission, connectivity is restored. Android starts a new process for the
   retained job. Local fixture ownership is initialized before the worker factory resolves
   tasks; the checkpoint confirms that the sender SDK is still closed at this boundary.
5. WorkManager executes the actual task. The worker restores the sender, delivers, acquires
   canonical original history and retires the exact command. An observer waits for the
   actual WorkInfo SUCCEEDED state and verifies the UUID/body/completion; it never invokes
   the worker. Resources close before the result is published.

Android 14+ WorkManager jobs occupy a namespace. The proof enumerates the application's
pending jobs across namespaces, correlates their WorkSpec ID and records the selected
namespace for read-only `get-job-state` inspection. The first run timed out because the
fixture queried only the default namespace. WorkManager had correctly registered and
held the job offline; the correction changes only its observation. Phase-specific timeout
diagnostics now distinguish preparation, offline waiting, queueing and completion.

The driver validates every PID/work/intent identity and bounds its stages. Cancellation
cleanup force-stops/uninstalls only its claimed proof package, restores the original
emulator radio settings, removes the reverse mapping and releases ownership. A lost radio
command response still requires restoration. Private fixtures are removed by the server
test; evidence contains bounded identifiers/digests and classified diagnostics.

## Evidence and limits

The successful scheduled run passes in 76s: two processes, one ordinary process death,
automatic OS job startup, one encrypted send, acquired original history, completion
retirement and restored emulator connectivity. The gateway verifies ciphertext, expected
sender, live device/conversation/room admission and zero early sends/admission failures.
The raw WorkInfo attempt count is recorded; it is not relabeled as a retry test.

Both previous native scenarios also pass with the same APK: the four-process sender
recovery proof retains three encrypted sends and no held-room send, and the direct worker
proof retains its one encrypted send and peer decryption. The combined regression takes
112s. All 62 Python driver cases pass, including metadata privacy, wrong identity/PID,
background Activity detection and restoration after a lost mutation response. Root checks
pass in 57s. [Measurement](measurements/chat-workmanager-2026-11-01.json) binds all inputs,
attempts, artifacts and results.

This accepts the tested Android scheduling/constraint/process-recovery path. Deliberate
interruption during sending, WorkManager retry/cancellation races, production login
recovery, incoming FCM/inline replies, Apple/web execution and capacity remain separate
criteria. Force-stop blocks normal Android job execution until the app is started again.
No complete PRD group, phone installation or live provider deployment is claimed here.

Continue remote older-history paging, durable drafts and safe pending cancellation,
then incoming notifications/replies, groups/media/actions, verification/key continuity,
backups, full Apple/web adapters, calls and meetings. Keep platform interruption coverage
explicit while completing those product paths.

The native HRIS workflow pins Platform `075e63dbdc0271ebf00bea4fd9756c7441408328` and runs all three
scenarios sequentially on its owned emulator. It archives bounded job/intent/checkpoint
evidence and excludes private fixture credentials. Hosted acceptance requires auditing
the corresponding run and artifacts; local results above do not imply that CI has passed.
