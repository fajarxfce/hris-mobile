# Original historical content acquisition

An existing Company history can belong to an older device and have no primary incoming task.
`AcquireCollaborationSpaceOriginalEvents` now accepts that explicit historical input alongside
its existing primary input. It retains content through the same bounded Company history and edit
pipeline. The result keeps historical coordinates and per-event outcomes; it does not acknowledge
references, advance recovery, retire a queue entry or create provider work.

The currently installed source must exactly match the captured historical source. Current
registration admits the actual native Company and device; snapshots must match that current
device, account, Company, native session, room and event. The historical traversal device remains
unchanged. Admission, metadata, registration, native ownership and source are rechecked around
I/O. A replaced runtime, confirmed denial, cancellation or uncertain write cannot return a
completed batch. Existing preparation deadlines and restriction cleanup remain.

Both input types share original/edit planning, deduplicated target reads, tombstone handling and
sixteen-source bounds. Missing keys remain an unresolved outcome. A later explicit invocation may
recover those references after keys arrive; this usecase starts no retry timer. The existing
primary overload still requires its exact Pending task, source and registered primary device.
No usecase calls another usecase, and no fake primary task is introduced.

Twenty-six new common domain cases cover historical/current devices, queue absence, malformed
and foreign identities, source and access races, deadlines, cancellation, classified failures,
missing keys then recovery, originals, edits, shared targets, tombstones, uncertain commits and
input snapshots. The existing thirty-three primary acquisition cases remain controls.

The accepted local suite contains 13,200 cases across fresh results and verified unchanged
controls. The focused desktop/browser suite contains 422 cases. Android release compilation,
lint, application checks, browser tests and Apple cross-compilation are included in the full
run. Hosted Apple simulator checks are tracked separately; these are not native SDK8 execution.

The key producer, listener and global recovery consumer remain disabled until bounded reference
and scheduling owners are ready. Space/Original alias content routing, reference completion,
full key-blocked queue liveness and native listener lifecycle remain open. No live deployment,
Starter change or phone installation is part of this source slice. All54 PRD groups remain open.

Two failed focused attempts remain in the evidence: the first stopped before tests because a new
assertion used `eventId` instead of `sourceEventId`; the second ran149 cases with one new fixture
failure. Its queue-removal helper required an initial queue which the fixture had cleared. That
case now explicitly starts with and asserts a primary queue, then removes it during acquisition.
Both corrections affect only the new test file; production and existing assertions are unchanged.
All149 identities from the failed attempt must remain in the accepted full results.

[Exact validation evidence](measurements/collaboration-original-key-content-2026-11-11.json).
