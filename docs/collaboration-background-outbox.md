# Background staging and delivery

`StageCollaborationText` and `DeliverCollaborationMessages` accept the shared
Selected/Background operation scope. Existing String entry points preserve selected
workspace behavior. Background uses the current native parent and original finite
company admission, validates the requested device, and never reads or changes
workspace selection. The caller owns a published finite background runtime reference;
these operations never restore, enroll or close a native parent implicitly.

Both scopes use the existing staging and delivery implementations. Preparation is
retained before native enqueue, uncertain attempts retain their transaction mapping,
and concurrent/cancelled work follows the same compare-and-set rules. Expiry during
preparation stops enqueue. Expiry after native commit leaves the original attempt
available for recovery with a fresh admission, without preparing a replacement ID.

Observed send receipts are appended under the captured account fence before remote
continuation is checked. Cancellation, expiry or native-parent replacement cannot
silently discard a receipt that was already returned. Later admission projects that
receipt before further queue work. A delivery pass may inspect the SDK queue again
with already-Sent mappings to retain additional event aliases; this is not a new
logical enqueue. The recovery test models an already-drained second SDK window and
asserts the same intent, transaction and original receipt. Its fixture rejects enqueue.

Staging now uses `collaborationContentResponseClosesRead`, as delivery/history already
do. `client.collaboration_sdk_unauthorized` stops the native operation without
restricting all local company history. Confirmed HRIS company/session/MFA denials
still restrict the retained projection, including under cancellation or failed cache
cleanup. SDK credential expiry and company access loss retain their distinct meanings.

[Evidence](measurements/chat-background-outbox-2026-11-02.json) records 12 added cases,
the initial incorrect resume-count expectation and its correction, focused checks
and required repo/browser checks. There is no new JNI/provider acceptance, employee
APK assembly or phone installation in this increment. Existing foreground callers
receive the classification fix; a complete background worker still needs shared
room-control, captured local discovery/reconciliation/draft scopes and orchestration.

Local reads retain a separate ownership contract; do not impose the remote grant's
60-second lifetime on offline facts. Continue all-company wake/reconnect/periodic
recovery, incoming notification projection and inline reply integration. Backend
push delivery remains disabled until actual receive/recovery acceptance.
