# Notification recovery across the transfer boundary

The native fixture now runs both existing production phases: protected device
input transfer, then account-owned reply recovery. A bounded input attempt can
finish transfer and exact cleanup before interruption leaves its copy pending.
The next input phase can finish with no device input remaining; the independent
reply phase still owns review and command reconciliation.

The fixture previously omitted that second phase. It now records both results
and accepts completion only when both finish. Exact original/copy identity,
SessionChanged review, zero sends, wrong-account preservation and cleanup
assertions remain unchanged. Production workers and deadlines are unchanged.

A deterministic application test cancels immediately after protected-input
retirement, reopens encrypted SQLite, and runs both production workers. The one
unchanged reply becomes held for the replaced session without a message command
or SDK send. Native proof adds Android callbacks, Keystore, process death and
system notification inline reply.

Validation: 29 Python driver cases, 658 fresh full-gate cases in301.911s, app/proof
builds, browser/root/format/architecture. App lint has zero issues; proof lint has
zero errors and47 warnings (dependency/tool updates and fixture manifest metadata).
The owned Android fixture passed90.721s with original emulator data retained.
[Evidence](measurements/collaboration-notification-phases-2026-11-06.json).

The earlier ac7668 native suite also passed all six result groups with one
verified APK, including original history/replay, offline recovery and paging.
Its intermittent transfer interruption is covered by the additional regression.
[Native evidence](measurements/collaboration-stable-history-native-2026-11-06.json).
