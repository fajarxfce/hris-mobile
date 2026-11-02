# Application recovery for notification replies

`CollaborationNotificationReplyRecoveryWorker` is a singleton application phase
inside the existing account recovery job. It has an eight-second total deadline,
uses one non-waiting mutex, discovers at most four retained input IDs, and gives
each reply attempt at most two seconds. Cancellation joins the active attempt
before another entry or company phase starts. It does not own a retry loop or
capture an Activity, foreground selection or native callback.

Begin receives the caller's captured native parent and checks it before storage.
An obsolete job cannot read or create the replacement account's recovery cycle.
An empty account completes without a history key or cursor. For pending work,
Begin/Advance/Settle/Finish use the [durable encrypted cycle](collaboration-notification-reply-recovery.md).
Advance commits the seek and in-flight marker before the attempt. The existing
[reply submission workflow](collaboration-notification-reply-flow.md) reproduces
commands and holds input only after reproduced review. Settle stores transient
retry outcomes; Finish includes an interrupted attempt. Earlier failures survive
a healthy later page and database/container reopen.

More entries return a continuation with their accumulated retry outcome already
stored. The last page finishes the cycle and returns backoff if any attempt still
requires retry. No invocation wraps or starts a second cycle. Confirmed company
AccessDenied/Unauthorized attempts leave input retained without requiring immediate
retry; the settle guard still rejects a replaced native parent. Other failures,
including validation and conflict, cannot silently clear the retry requirement.
Held input is excluded from automatic discovery and remains available for explicit
review. Neither a denied attempt nor a metadata transition deletes reply text.

The global worker reserves eight seconds for replies, twenty for priority recovery
and fifty for the ordinary company turn within its ninety-second processor.
Failure in either independent phase does not skip ordinary company progress.
Any independent failure beats a healthy immediate continuation, including before
the last company page. The account wake is acknowledged only after the independent
phases and the company sweep finish; an arriving newer wake survives exact-generation
acknowledgment. At most one processor and one waiting invocation are admitted.

The real application container registers this phase and the global worker uses it.
The isolated native company-only fixture supplies an explicit empty reply phase;
that fixture is not evidence for native reply handling. Tests cover real encrypted
SQLite/application recovery separately from global virtual-time lifetime and wake
coordination. The latter use actual domain operations with explicit raw outcomes.
See [recorded checks and limits](measurements/chat-notification-reply-worker-2026-11-02.json).

Native publication journal, admitted action issuance, Android RemoteInput and iOS
notification callbacks, explicit failed-reply review UI and notification-input
process-death acceptance remain pending. Existing provider interop cases exercise
other chat recovery paths. Chat push remains off until native behavior is usable.
