# Organization directory reads and acquisition

Use cases own local account/session/audience checks and read admission. The local
observer has no clock, selected HR company or network/SDK dependency. A normal
credential expiry or same-account native-parent rotation does not hide saved facts.
A replacement local session requires a new explicit request owner; account-owned
storage retains the same encrypted facts. Admission failure/denial cancels facts;
zero-capacity boundaries discard queued old facts on owner/admission replacement.
A terminated storage subscription is reopened explicitly, never by a timer or age.

Refresh is a singleton owner of one acquisition mutex. Each invocation requests
at most one unfiltered page of 50; default continuation respects the persisted
terminal cursor and performs no admission clearing. Explicit restart begins a new
generation only after the old one completed; incomplete refresh always resumes.
The 15-second timeout includes local reads and mutex waiting. Original native hard
expiry, wall-clock rollback and monotonic elapsed time are checked before/after
acquisition and publication; native renewal cannot extend the captured attempt.
Admission is reread after mutex waiting, so a concurrent newer denial also gates
a terminal no-op. The exact captured access revision is cleared only after fresh
admitted acquisition and durable page publication. Confirmed server denial reaches a separate bounded
2-second non-cancellable retention attempt, including cancellation at delivery.
Prior denials dominate pending/failed retries. Ordinary provider outage or local
credential expiry does not write a server-access denial. Newer denial wins CAS.

No UI cutover or new group push/SDK ownership is activated here. Product still uses
company chat; durable group identity/device commands and SDK adoption/hard-parent
recovery are next. All 54 communications PRD groups remain open. Phone release 29
artifact is already verified and awaits the existing availability question.

Validation: 22 focused behavioral cases pass; root `:check`, Android-host tests
and Android dev compilation pass in 419.463 seconds
(2,503 fresh cases). Browser domain tests pass in
81.676 seconds (968 cases). Final source inputs
are identical across all three gates. Real application DI resolves the observer
and the singleton refresh instance. Format, architecture and detekt pass.

A failing slow-consumer test first reproduced a stale row delivered after an
admission denial. Guarded access now feeds one final cancellable facts subscription;
the same regression passes on JVM and browser. Earlier task-selection and test
compilation errors are recorded in [evidence](measurements/collaboration-space-directory-usecases-2026-11-04.json).
