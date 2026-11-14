# Admitted empty history-page confirmation

An acquired raw history page can contain no message references and still have a continuation.
ConfirmCollaborationSpaceHistoryChanges checks such a page under the current account/Group,
using its exact canonical binding, opaque source token and captured index version. It uses the
existing null-projection publication contract; no event ID or content request is invented.

The typed receipt must identify the same source generation and cover the frozen upper revision.
Absent source/index states remain explicit. Nonempty pages, restart markers, mismatched owners
and invalid cursor windows cannot become empty confirmation. Captured collections are detached
before suspended reads. Conflicts and uncertain failures return once without internal retries;
denial, account replacement and cancellation discard late results.

Ten shared tests cover both actual namespaces, opaque tokens, source/index absence, continuation
coverage, invalid inputs, contention, uncertain responses, invalid receipts, denied access,
replacement accounts, pending cancellation and caller mutation. Focused57/full17042 preserve all
17022 prior case identities plus20 desktop/browser executions. The app graph resolves the new
use case. Apple compilation does not claim actual new Apple execution, which remains open.

The result is a checked receipt, not cursor completion. The existing repair owner still needs
atomic cursor/unresolved handoff, destination replacement handling and fair finite continuation.
No worker, observer, timer, UI, native runtime, phone build or live capability is activated here.

Evidence: `docs/measurements/collaboration-history-empty-page-confirmation-2026-11-14.json`.
