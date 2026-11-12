# Next native key-retention proof

The seven accepted native scenarios prove initial reconciliation, a lost local write
reply, bounded incoming recovery and offline reopening. They do not yet prove that a
new key survives arrival while another key receipt is being retained. Keep those seven
scenarios and their four-event history assertions unchanged.

Use an additional receipt-focused scenario with fictional identities and fresh emulator
storage. The adopted SDK8 binding already exposes `Room.discardRoomKey`; no SDK rebuild
is needed. During physical staging, after capturing the existing four-event evidence,
discard the peer's outbound room key and send one additional encrypted event. Retain its
ID separately and do not sync the source afterward. This prepares the new key without
putting peer restoration and sending inside the application's five-second retention
deadline or reopening a legacy peer after handover.

In the application phase, run the actual process source owner and repositories. Commit
the initial receipt transaction, hold its reply, then perform one real source sync to
receive the newly prepared key. Verify the first witness is durable and has not been
acknowledged. Release its reply as a storage failure. Exact readback must preserve the
first receipt without rewriting it; the successor must commit a new recovery obligation
before its own acknowledgement. Keep the existing three-attempt and five-second limits.

The sync child may update demand concurrently. Check receipt and recovery ownership,
not only whole-snapshot equality. Room predicates belong to `historyRecovery`; unknown
local Original metadata may require account-wide recovery rather than a claimed targeted
match. Record bounded attempts, two distinct committed receipts, acknowledgements and
the unchanged native instance. A separate offline process must read the final witness
and recovery obligation without opening the SDK. Follow with a finite quiet-period
request measurement.

Use a separate typed result/driver for this scenario because the extra real event changes
the physical head and timeline. Reuse existing protected fixture operations and staging
validators. Own every lease, collector, barrier and native task; release them on failure
and retain failed isolated userdata. Do not use the user's phone, Bima's retained stores,
synthetic key callbacks, duplicate key imports or changed production deadlines as proof.

The eight-case suite and full Mobile gate now accept this scenario; see [native late-key evidence](collaboration-native-late-keys.md).
The quiet-period check distinguishes bounded10-second cursor long polls from repeated business
requests. Live Group rollout and native Apple SDK8 acceptance remain separate work.
