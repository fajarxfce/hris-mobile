# Late-key worker recovery across process reopening

The actual registered incoming worker now has a native test that consumes both retained
key-recovery generations after reopening the application process. It starts from the existing
real-key/lost-write-reply scenario, verifies the retained obligation offline, then reconnects
and invokes the registered task using the fixture scheduler. Registration/bootstrap are read
from storage and are not recreated. The recovery phase isolates the existing receipt from
new source collectors so that its exact identity can be checked through completion.

The finite worker run must drain both generations within eight turns, reject a turn that makes
no progress, preserve the receipt and outgoing journal, and make an extra empty turn a no-op.
The Company projection must contain the original and the new encrypted peer message, preserve
the original's latest edit and the other peer message's tombstone, and leave no unresolved
references. Nothing is relabeled into Space message/receipt storage. A further offline process
must read identical content, order, revision and completed recovery with no SDK opening or
additional server traffic, then release all storage/resources.

All nine native cases pass, retaining the original eight case identities and their assertions.
The new single case also passes independently. There are21 proof unit tests and65 driver tests.
Full Mobile validation is still running; its result is not claimed here yet. No production
policy, retry timer, SDK artifact, live schema or feature flag changes. The first static gate
rejected a long fixture method; pure payload assertions now belong to the existing evidence
class, while the host owns repository reads. All assertions remain. The first suite launch
failed port reservation before starting an emulator; later availability allowed the identical
source/APK to run. Failed snapshots and launch evidence are preserved.

This verifies the actual registered task through a fixture OS scheduler. It does not prove
automatic WorkManager relaunch, signed-in phone behavior, chat latency, native AppleSDK8,
or live Group rollout. All54 PRD acceptance groups remain open.

[Validation evidence](measurements/collaboration-native-late-key-recovery-2026-11-12.json).
