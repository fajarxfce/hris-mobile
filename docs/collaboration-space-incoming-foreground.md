# Foreground incoming startup

Opening the application requests recovery when the current account has an admitted local
organization root and completed bootstrap. Unfinished incoming work resumes with its existing
scan, FIFO order, provider phase, retry time and durable wake. An idle queue receives one new
bounded inventory pass on cold startup or a changed bootstrap. A warm foreground return
resumes retained work without creating an idle scan. An explicit content/overflow refresh retains its existing semantics.
Repeated foreground entries between inventory pages cannot add another scan generation.

The application collector observes the stable domain startup coordinates. It owns one pending
request identity until confirmation, cancels on parent/root replacement and stops with the
foreground scope. Storage errors preserve an uncertain identity; confirmed denial or absent
root retires it. A confirmed, credential-free bootstrap capture remains owned by the process, under the
foreground mutex. Duplicate publications do not reschedule within an entry; a later warm
entry requests Resume only. A failed subscription ends the pass without a retry timer. Overlapping
host entries serialize their collector lifetimes.

Android starts this collector only inside ProcessLifecycleOwner STARTED and routes incoming
requests through the existing conflated RequestedSyncWorker lane. Application.onCreate still
owns the native parent, including background WorkManager starts; that process lifetime creates
no new incoming scan. WorkManager retains durable retries and the existing periodic fallback.
The non-Android foreground job owns both its existing worker and this collector. Apple starts
its existing parent lifetime from AppleAppHost and cancels the collector with foreground work.
Compilation and model tests do not claim Apple native Matrix execution.

The request usecase owns admission, demand validation and scheduling. Its repository still
atomically retains demand and wake. Startup resumption only schedules already-retained work;
it never acknowledges or recreates a wake. If completion races with that schedule, Requested
execution observes no wake and exits. No new SDK, transport loop, ViewModel helper or repository
access was added to the application collector.

The native scenario starts the actual foreground collector, awaits its scheduler enqueue and
executes the registered incoming worker. It no longer seeds that worker's demand manually.
It must retain visible content before provider EOF, finish bounded work, ignore an already
covered head and reopen unchanged encrypted history in a second offline process. Existing
manual-provider and interrupted-original-delivery controls remain part of the same gate.

The focused gate passes 198 cases and full validation passes 10,982 cases with fixed source
and library fingerprints. Group source compilation and 43 protocol cases pass. The five
native cases pass in 249.258s on a fresh 2560 MiB, two-core emulator. The automatic
startup scenario needs 2 worker turns and exactly two scheduler requests,
with Matrix/native request counts checked against the retained budget. The emulator stopped
after the experiment; existing sender stores and the user phone were untouched. Realtime raw event/key hints, unknown-room admission and incoming push
routing remain separate work. No SDK artifact, live deployment or phone performance claim.
All 54 communications acceptance groups remain open.

[Measurements](measurements/collaboration-space-incoming-foreground-2026-11-10.json).
