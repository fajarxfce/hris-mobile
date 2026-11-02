# Native parent readiness during application startup

The single `MaintainCollaborationRuntimeOwnership` collector publishes a valid
native parent before waiting for the first workspace observation. Its initial
foreground owner is empty; an observed company can then add foreground ownership.
This lets admitted background work find its native parent while company selection
is still loading. It does not open a runtime or acquire remote credentials.

The collector keeps one snapshot for the exact local session, audience, account
and native family. When tokens change within that parent, it seeds the last
observed workspace result instead of temporarily clearing foreground ownership.
A replacement parent starts with no foreground snapshot. Logout and disposal clear
ownership; a pending workspace subscription remains owned and cancellable.

Observation time advances with a monotonic clock and a nondecreasing wall-time
baseline. Repeated session/workspace observations cannot renew the time budget
when the device clock moves backward. A forward adjustment also cannot be undone
by a later rollback. The minimum hard expiry observed for the same native family
remains binding. The snapshot survives transient session-read failure and expiry
so a subsequent observation cannot recreate an elapsed allowance.

The existing foreground-before-parent timers remain. A pending initial workspace
does not suppress hard parent expiry. Policy, module and membership denial retain
their company gate even when cleanup fails. Late native cleanup cannot publish an
obsolete foreground owner. Captured local facts keep their independent lifetime.

This time policy is deliberately conservative when fresh workspace timestamps are
created while the system clock is far behind its earlier observation. It prevents
expired authorization from reopening; it is not a clock-synchronization service.
The snapshot is bounded to one parent per collector and is not persistent across
process restart. Live background acquisition still validates its own server grant.

[Validation](measurements/chat-parent-readiness-2026-11-02.json) covers delayed first
workspace, same-parent token rotation while the replacement observation is pending,
logout/replacement, parent expiry before any workspace emission, repeated emissions
under clock rollback, transient read failure and forward/backward clock movement.
Existing observer exclusivity, denial, timer renewal and late cleanup cases remain.
Initial stream expectations explicitly include the new parent-only phase.

Application startup readiness still needs an explicit consumed publication signal
for the future all-company coordinator. `ApplicationCollaborationLifetime.state`
currently starts at `Success(null)`; that default is not evidence that native
retention was processed. Reuse the existing collector, and keep failed cleanup
separate from current parent identity when designing that signal. Do not start a
second collector or use a raw FCM company/device field as authority.

Durable all-company wakes, fair directory/room cursors, receive/reconnect/periodic
recovery, notification privacy/read/mute and encrypted inline replies remain open.
No provider, incoming-FCM or physical-device acceptance follows from these tests.
