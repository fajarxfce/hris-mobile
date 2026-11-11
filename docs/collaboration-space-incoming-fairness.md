# Fair service for incoming conversations

Original provider and pending-reference continuations now yield through the existing
`DeferCollaborationSpaceIncomingRoom` owner. That owner rotates the exact retained task with
one conditional write and readback. A successful unfinished page has no retry deadline;
actual failures retain their separate backoff. Published continuations already use this path.

The worker reserves one eligible inventory callback before the four-room primary turn.
This keeps a slow room from consuming the shared 90-second deadline before inventory runs.
Existing source, capacity, retry and 10-second inventory limits remain. No additional queue
claim, use case, repository, collector, native client or dispatch transaction was introduced.

Eight application cases exercise all-room service across long provider/reference histories,
newly arriving work, slow callbacks and worker timeout, cancellation, inventory backoff,
admission denial and independent room failures. Four existing cases now check the updated
continuation or execution-order contract. Existing domain policies and actual encrypted
demand storage remain controls. The CI workflow explicitly includes the eight new cases.

Validation: 320 focused desktop/browser cases and 12,748 full local cases, with exact
source and library hashes, prior case identities and unchanged dependency closures checked.
The application tests use the real worker and queue policy with repository/runtime fixtures;
they do not measure native SDK performance or prove drainage when 64 rooms wait for keys.

Keys-only demotion, durable key passes and the existing SDK8 stored-key listener consumer
remain separate work. Raw observation and the Original producer stay disabled. No phone,
Starter or live-server behavior is claimed by these checks; all 54 PRD groups remain open.

[Exact validation evidence](measurements/collaboration-space-incoming-fairness-2026-11-11.json).
