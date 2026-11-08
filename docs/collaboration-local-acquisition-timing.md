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

Source acceptance passed on 8 November 2026: 5,749 fresh cases with zero failures,
errors or skips in 1,290.851 seconds. Root check, real browser storage, Android
compilation/lint, production web/iOS source, format, architecture and Detekt passed;
4,487 application inputs and82 library files remained unchanged. The focused gate
also passed37 cases, including eight native diagnostic/lifetime cases and six
workspace storage cases. [Exact checks](measurements/collaboration-local-acquisition-timing-2026-11-08.json).

A separately verified APK69 and actual acquisition/read capture remain required
before attributing the pre-send delay. Transaction identity, admission and retry
behavior are unchanged. Release68 stays installed until the next device acceptance.
Native Apple execution, measurement overhead and the remaining54 PRD groups remain
separate work. The workspace module stays in HRIS; only reusable observability names
can be considered for the starter after Mobile acceptance.
