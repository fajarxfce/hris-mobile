# Foreground chat delivery

Android's persistent WorkManager queue must not be the foreground send trigger.
Appending to a retrying job chain can hold a new message behind an earlier room's
failed request. A captured phone52 attempt confirmed HTTP200 for Bima while another
owned room, with no registered recipient device, repeatedly returned403. This does
not mean Bima must be online; his confirmed key/device remain available offline.

The Android host now listens to delivery requests through one conflated in-process
signal. It owns a foreground worker only while the process lifecycle is STARTED,
stops and joins it when leaving that state, and resumes from durable commands on
return. Explicit signals interrupt its retry wait. The platform callback carries
only a task key, never message/account data. Unrelated tasks retain their behavior.

The same admitted delivery worker, repositories and guarded SDK queues perform the
actual work. No message is recreated, no acknowledgement is invented and no private
store is replaced. An existing active worker can still finish its owned turn; this
change does not bypass writer exclusion or promise a fixed network latency.

WorkManager remains the persistent background mechanism. New chat delivery requests
use the existing bounded two-slot coalescing policy rather than appending unbounded
chains. Previously scheduled jobs and encrypted intents remain intact. Foreground
wake failure cannot erase an already committed command. A cold/background launch
does not start a foreground loop; WorkManager performs admitted background recovery.

Common regression tests cover interrupting a30-second backoff, retained wake across
owner cancellation/restart, and100 concurrent wake signals without concurrent writers
or an unbounded queue. Startup consumes an already retained wake instead of running
the same scan twice. Android compilation, quality, local delivery and phone acceptance
are recorded separately; source presence alone is not a measured latency result.

Accepted full gate:7,436 fresh cases in967 suites, zero failures/errors/skips,
4,270 unchanged inputs, zero Android lint and Detekt findings. The actual Android
WorkManager proof passed: a new synthetic durable wake was acknowledged in436ms
while the original OS request stayed in30-second retry backoff and the appended job
remained BLOCKED. Stop/restart consumed exactly one retained wake. Fixture/package
and owned emulator cleanup completed; no SDK login, provider call or message send.
This measures scheduler behavior, not physical-phone delivery latency.
[Evidence](measurements/collaboration-foreground-delivery-2026-11-07.json).

The proof scenario is `foreground-scheduling` in `MatrixProofInstrumentation`.
Build the proof APK before starting a dedicated fresh emulator, then invoke it with
an explicit serial. Keep original Bima and phone data separate from this fixture.
The three common scheduling regressions run with `:core:worker:desktopTest`.
Android process lifecycle2.9.4 adds the official savedstate1.3.1 metadata/AAR to strict
verification; their exact cached bytes were independently matched to Google Maven.

Release53 from08545820 is installed after exact APK verification and four original-data
room entries offline/online. The physical phone received an update in place with its
original application identity retained; no phone UI was driven. Physical message
latency remains open pending actual observations. [Release evidence](measurements/collaboration-release53-2026-11-07.json).
