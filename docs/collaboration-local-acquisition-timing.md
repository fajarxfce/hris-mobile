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

Release 69 from `e457e3676158f544cc86d19eef886aa80836773a` is now accepted
on the original Bima emulator and installed in place on the authorized phone. The
APK hash is `22d3f6f2c9f3e3e256fbeaf9ad336026dd07e30f321ff408eee825529c39d022`.
Phone identity and first-install time were preserved; no phone UI was driven.
The emulator is stopped with its data retained. One new original, earlier history,
the editor draft and draft list preview survived a cold offline restart. Four
screenshots were inspected and a passive check found no fatal exceptions.

The 180.054-second emulator request window had zero 403 responses, two initial
401 responses for the optional default secret-storage key, one successful refresh,
and two subsequent 404 responses. One encrypted send returned 200. Host tap to
request took 3,113 ms, and response headers arrived 235 ms later. Coarse UI polling
first observed Sent at 5.054 seconds. This is not a demonstrated improvement over
release 68, and does not establish physical-phone latency.

The sending worker recorded 113 workspace reads (726 ms summed intervals) and
86 local-access reads (193 ms). The capture also includes a separate idle worker,
other readers, and post-acknowledgement work; these sums are not end-to-end delay.
Three native queue timelines took 267, 115 and 136 ms to construct. Their completion
times fall within completed-queue settlement, staging and delivery respectively.
Source inspection confirms all three leases eagerly build a native timeline,
although queue inventory/settlement does not use it. Investigate demand-driven
native timeline acquisition while retaining the paused writer, async FFI queue
initialization, exact original recovery, current admission and cancellation cleanup.
Do not skip the old-queue inventory or cache authorization to remove this cost.

[Exact runtime, component intervals and installation evidence](measurements/collaboration-local-acquisition-release69-2026-11-08.json)
records the one original without its body or owner identifiers. Measurement overhead,
native Apple execution and all 54 PRD groups remain separate work. Workspace remains
HRIS-only; only reusable observability names belong in the starter.
