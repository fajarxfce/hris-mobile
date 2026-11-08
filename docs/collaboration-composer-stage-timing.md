# Composer submission and recovery timing

Release66 still took6.5–8.8 seconds from an emulator tap to the encrypted request,
while Matrix response headers arrived in175–205ms. That sample does not isolate
storage, scheduling or recovery cost. This candidate measures those existing
boundaries; it is not yet an accepted latency improvement or device measurement.

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

[Candidate checks](measurements/collaboration-composer-stage-timing-2026-11-08.json)
retain failures and source/report fingerprints. First focused run passed48 behavior
cases but failed the method-length quality gate after additional nesting. The candidate
keeps the handoff together, shortens verbose type names with import aliases and names
the next draft before encoding. A second run and full/browser/platform checks remain
required. The exact generic timing port waits for Mobile acceptance; Starter remains
e170ada. Release67 scripts are prepared only; no67 APK or new send exists.
