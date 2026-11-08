# Composer submission and recovery timing

Release66 still took6.5–8.8 seconds from an emulator tap to the encrypted request,
while Matrix response headers arrived in175–205ms. That sample does not isolate
storage, scheduling or recovery cost. The implementation measures those existing
boundaries. Source validation passes; it is not a latency improvement or device measurement.

- `CommandHandoff` surrounds the encrypted outbox repository's existing handoff,
  including dispatcher acquisition, transaction preparation and stable-result mapping.
- `WorkScheduling` surrounds the platform schedule repository's existing request,
  including lazy scheduler acquisition and the mapped platform result. It logs no key.
- `RecoverComposer` records the new-original recovery result inside the worker.
  A successful Boolean means the read/materialization succeeded; its value can still
  request another worker pass. It is not a server acknowledgement.
- `RecoverLegacyDrafts` separately records the legacy frozen-draft recovery result.
  The existing worker combines both results afterward. Failure in the first source
  must not be attributed to the second phase just because it ran last.

Existing finite coroutine traces own these measurements. Nested spans share their
parent's ephemeral trace and retain independent span IDs. Timing uses a monotonic
clock; nested/overlapping intervals must not be added as end-to-end latency. Only
closed operation/stage names, durations and outcomes are emitted. No account,
conversation, task key, body, token or exception text is added.

Domain admission, command identity, transaction bounds, retry policy and scheduling
semantics remain unchanged. The encrypted repository still performs the same raw
operations; no orchestration was moved into a helper. Actual local commit and Matrix
acknowledgement remain separate. Cancellation propagates through the existing owners.

[Recorded checks](measurements/collaboration-composer-stage-timing-2026-11-08.json)
retain failed attempts and source/report fingerprints. The full gate passes5,759 fresh
cases/772 suites, zero failures/errors/skips, on4,479 unchanged application inputs and
82 unchanged library files (49 non-document inputs). Actual Chrome runs schedule3 and
outbox28; Android compile/lint0, web/iOS source, root and quality checks pass. Native Apple
execution is not claimed. Scheduling tests retain lazy acquisition, suspended completion,
mapped failure, cancellation and content-free logging; storage/worker contracts remain.

Earlier focused checks retained48 passing behavior cases but found method-length issues
introduced by wrapping/formatting, then two generic fixture type-inference errors. The
handoff stays together with concise type aliases and named next-draft/capacity-page values;
no orchestration helper or rule suppression was added. Fixtures retain explicit Unit where
a thrown lambda cannot infer a type. These corrections pass the final full gate.

The exact three-file generic timing port waits for source commitment; Starter remains
e170ada. Release67 scripts are prepared; no67 APK or new send exists at source acceptance.

## Existing request correlation

The release66 log already correlates43 of71 Ktor HTTP completions with its two delivery
worker traces; none share the three local-submit traces. Approximate stage windows show
three HTTP completions during each `StageMessage`, six during each `DeliverMessages`,
and six during each retirement-history acquisition. All43 worker-correlated responses
were200. These are separate from the native Matrix send, which uses another observer.

[The retained analysis](measurements/collaboration-worker-request-attribution-2026-11-08.json)
uses exact ephemeral trace identity, but stage-window assignment uses guest log completion
times and has3ms boundary slack. Parent/child intervals overlap, so row counts and duration
sums must not be added. It does not identify request paths or justify removing live
admission checks. It required no new send, device interaction or runtime change.
