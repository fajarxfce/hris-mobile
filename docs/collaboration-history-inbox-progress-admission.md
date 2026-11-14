# Admitted history inbox cursor handoff

RetainCollaborationSpaceInboxHistoryChanges freezes checked publication outcomes before any
suspension, applies the existing handoff policy, and commits cursor plus unresolved references
under current local account/Group admission. It invokes repositories directly and does not call
another use case. The immutable canonical source is already captured and authenticated; no new
binding query, history read, transport call, timer or implicit retry is added.

The result must be the exact next source checkpoint and proposed state. Conflict and uncertain
failure return once; the owner must inspect retained state before deciding what follows. Admission
is checked again after mutation, so a replaced or denied account cannot receive a late completion.
Cancellation propagates. Empty continuations retain their cursor and absence invents no revision.

Ten shared cases cover both namespaces, empty/absent pages, conflict and uncertain responses,
invalid publication/returned checkpoints, denial, replacement session/account/audience, pending
cancellation, and caller collection mutation. The app graph resolves the owning data-module
factory. Source lifecycle, pending-reference admission/handoff and global worker activation remain
separate work. Apple compilation does not claim actual new Apple execution. No phone/live change.

Evidence: `docs/measurements/collaboration-history-inbox-progress-admission-2026-11-14.json`.
