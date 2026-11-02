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

## Processed publication and application lifetime

`MaintainCollaborationRuntimeOwnership.publications()` emits a typed publication
after retention processing returns and cancellation is checked. It carries the
processed retention, ownership outcome and cleanup outcome separately. A valid
parent remains explicit when bounded cleanup fails; its presence never promises
that a native store is ready. Unsupported platforms still reject acquisition.
A failure outside the publication means this observer did not process a retention.

The existing owner-result entry maps the same publication, preserving confirmed
ownership denial ahead of cleanup failure. Both entries share one exclusive
collector and cleanup path. They cannot observe or close each other's resources.

`ApplicationCollaborationLifetime.state` distinguishes Pending, Observed and
Stopped. A default state is no longer evidence of an observed empty parent. The
application consumes the typed stream once. Close marks Stopped immediately, then
cancels its owned job; atomic state updates reject late publication. Source failure
or ordinary completion also clears the previous publication. This instance never
restarts implicitly. Cleanup ownership and `closeAndJoin` remain explicit.

Existing selected-owner task-key scheduling remains unchanged. Parent-only
publication does not schedule selected-company delivery, open a native runtime or
perform network work. A future all-company coordinator can consume the processed
parent, but every acquisition still needs current server admission and registry
checks. Raw FCM identity fields remain unsuitable as authority.

[Publication validation](measurements/chat-parent-publication-2026-11-02.json)
covers pending initial session/workspace, processed parent with failed cleanup,
company denial priority, mutual collector exclusion, replacement/logout, repeated
start, late noncancellable cleanup, source exception and normal completion. Four
new domain and six application cases pass alongside existing startup/DI checks.

Durable all-company wakes, fair directory/room cursors, receive/reconnect/periodic
recovery, notification privacy/read/mute and encrypted inline replies remain open.
No provider, incoming-FCM or physical-device acceptance follows from these tests.

The [all-company consumer](collaboration-company-worker.md) now uses this processed
state. A changed native parent requests durable recovery; same-parent renewal does
not replace its wake. The worker owns cancellation on parent change, independently
of selected-company delivery. No publication itself admits SDK acquisition.
