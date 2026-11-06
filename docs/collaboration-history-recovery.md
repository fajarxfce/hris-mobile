# Admitted latest-head history recovery

The delivery worker selects its recovery anchor through the domain
`collaborationReceiptRecoveryAnchor` policy. A retained `Staging` or `Staged`
command requires the current head. An older `Sent` command cannot pin that
missing-acknowledgement recovery to a distant historical window. When no uncertain
delivery remains, confirmed commands keep their original retirement anchor.
Selection does not acknowledge a send: account/device/room admission, original
sender/content/operation matching and durable receipt reconciliation still apply.

A worker regression retains an old confirmation whose original is unavailable,
places later messages outside its window, loses a newer send response and restarts
the application container. Before the fix, the newer accepted command remains
pending. With the new policy it recovers and retires exactly once, while the older
command stays retained, without another enqueue or resume. This test uses actual
encrypted application storage and simulated provider history; physical-phone
latency is a separate acceptance step.
[Reproduction and phone observations](measurements/collaboration-pending-delivery-2026-11-06.json).

`ReopenCollaborationHistory` accepts either a retained event anchor or an explicit
request to resolve the current head. A known anchor keeps the existing behavior. When
no anchor is supplied, it reads the SDK head only after live account/device/conversation
admission, validates the complete owner/device/room/event tuple, and re-admits the same
ready room before preparing shutdown. A null head leaves the runtime and history alone.
It cannot acknowledge a command or certify that all history is acquired.

Every lookup, shutdown, restore and publication boundary retains company denials and
checks cancellation/ownership. A malformed or foreign head, changed room, missing live
access or late cancelled response cannot trigger shutdown/restoration. The existing
native adapter still refuses to interrupt an active queue writer and still restores
only initialized protected SDK state; no enrollment or bootstrap is introduced.

The application delivery worker can now recover focus capacity after a lost native ACK
without already knowing the event ID. It performs one admitted reopening, reacquires
original history, recovers the receipt and reconciles the command before considering
another send. A fresh head is independently validated again by acquisition. A missing
original remains pending; bounded head windows do not claim complete provider history.
Initial runtime-opening/close-pending failures use retry backoff as well as the same
failures during room work. They do not discard commands or trigger extra enrollment.

When incoming foreground history fills the native focus cache, explicit Refresh cancels
the old child, performs the same admitted reopening and acquires a fresh child. Owned
local rows stay available throughout. Stopping during a late reopen cannot restore or
publish another timeline. This is a finite explicit recovery, not an automatic reconnect
loop or native send initiated by the screen.

Six additional domain cases cover current/absent/unowned/malformed heads, failed lookup,
owner/company/room loss and uncooperative cancellation. Two worker cases cover lost ACK
plus full focus and initial opening/closing backoff. Two thread cases cover explicit
capacity refresh and cancellation during a late reopen. The worker integration uses
real encrypted stores with simulated native/wire adapters, not actual provider proof.
Actual native worker/WorkManager interoperability, complete remote history traversal,
rich messages, push actions, cryptographic key continuity, calls and meetings remain.

[Validation](measurements/chat-history-recovery-2026-11-01.json) binds nine source hashes:
focused checks pass in 169s and full checks in 240s. Counts are 2,710 desktop, 358 domain
browser, 58 presentation browser, 170 Android host, 14 worker and two binding tests;
zero failures/errors/skips. Format, architecture, detekt, app Wasm and Android build
artifacts passed. Android lint has zero errors and one existing warning. Initial fixture
formatting and successful compiler-cache fallback are recorded. Artifacts are not installed.
