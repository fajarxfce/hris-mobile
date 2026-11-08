# Local and SDK acquisition timing

Release68's three transport reads took30–53 ms each, while staging still took455 ms
and tap-to-encrypted-request2,957 ms. This identifies an unmeasured interval; it does
not establish which local store, ownership check or native operation caused it.

The next diagnostic source observes existing workspace and local-access reads through
`measureAppResult`, including their storage/mapping result. `WorkspaceRead` and
`LocalAccessRead` are finite names. No record, company, permission, key or token enters
these diagnostics. Repository, datasource and use-case responsibilities are unchanged.
The workspace module remains HRIS-only; do not copy it into Starter.

The existing Android `traceSdkAcquisition` previously recorded only exception categories.
It now records elapsed time and a closed outcome for each existing native acquisition
stage, retaining failure classification and the original thrown object. It neither
installs a coroutine context nor checks cancellation after a successful raw acquisition:
a late resource must reach its owner for cleanup. Tests preserve this handover when
the owner is cancelled, single invocation and parent trace context, plus failure and
cancellation redaction.

Source is currently unaccepted under `.work/local-acquisition-timing-2026-11-08`.
Require the focused SDK/workspace/list/observability checks, full root/browser/Android/
iOS-source/quality checks, and a separate accepted APK69 before making new runtime
claims. Original transaction identity, current admission and retry behavior are not
modified. Release68 stays installed. Native Apple execution and capture overhead remain
separate; the remaining54 communications groups are still open.
