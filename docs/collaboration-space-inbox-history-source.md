# Canonical history and inbox source identity

The pure domain policy captures at most 16 events from the same binding-selected history source
as the thread. SPACE uses Group storage; LEGACY uses the actual Company and conversation. A Group
recovery copy of a legacy room cannot replace its canonical visible source. Complete original
aliases, hiding, deletion and edit facts survive capture; contradictory rows remain failures.
Replacement events may point to their authenticated original without becoming new activities.

Exact owned command lookups include archives and keep both Group/Company namespaces. One proven
original retains its actual identity. Two proven originals remain explicitly unresolved, as does
a possible queued or unstamped legacy original. A proven match in one namespace cannot hide
uncertainty in the other. Known different room, body or conversation and cancelled commands do
not block unrelated history. Failed or corrupt reads must never become nullable absence.

Unresolved results allocate no anchors, create no receipt and start no polling retry. The existing
owner must retain durable work until relevant facts change or bounded restart recovery runs.
Identity selection cannot clear a failure, downgrade SENT or overwrite pending edit content.
Those remain publication/operation responsibilities under current source and index revisions.

26 shared methods pass focused 46/full 16,547 locally, preserving all 16,495 previous identities
plus 52 JVM/browser executions. Apple test compilation passes; actual execution of these new
methods needs a later exact-source hosted control. No application graph or live service changes.

Next: admitted source acquisition and bounded preview publication in existing incoming owners,
17-candidate fallback for up to 16 hidden originals, pending-edit and monotonic delivery rules,
and explicit durable handoff of unresolved local identity before incoming completion. Ordinary
incoming failures currently retry after30..300s; Unresolved must not be mapped to that path.
Finite catalog/history backfill is also required when content predates a canonical binding. Required projection
must participate in incoming completion, including retained key-history replay without new merge.

Evidence: `docs/measurements/collaboration-space-inbox-history-source-2026-11-13.json`.
