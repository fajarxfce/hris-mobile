# Finite inbox repair checkpoints and exact lookup

A local repair checkpoint retains one captured pass, its opaque reference cursor, the latest
coalesced demand and the last completed demand. Requesting more work preserves an active pass.
Completing a page advances only that captured pass; completing its last page leaves later demand
pending. A finished pass becomes idle even when some original references are still pending.
Beginning an existing pass or replaying its latest demand keeps its checkpoint unchanged.

Pure domain policy validates Request, Begin, Advance and explicit stale-cursor Restart transitions.
Unchanged page cursors and stale completion attempts cannot advance. The data repository stores
these immutable facts under an encrypted account/Group checkpoint and exact raw compare-and-set.
Uncertain commits remain readable after restart, cancellation propagates, and old writers cannot
overwrite newer demand. A no-op preserves ciphertext. Existing protected history keys and
session-independent associated data preserve ownership across login renewal. The metadata contains
no message text, transport state or native-client dependency.

The repair repository also supports exact lookup by conversation and original Group command ID.
It authenticates one HMAC document coordinate with its coherent index checkpoint. Completed or
absent references return null; it does not page unrelated messages or guess aliases from equal IDs.
This enables a future single command observer to target changed originals. A persisted finite pass
will recover missed progress after restart. The application owner and admission use cases are not
integrated by this storage change; no index observation is used as a command-work trigger.

Validation adds 21 shared methods: nine transition policies, nine real checkpoint-storage cases,
and three exact-reference storage cases. JVM/browser execution covers finite completion, later
coalesced demand, stable no-ops, stale cursors, competing writers, lost committed responses,
cancellation, key preservation, login renewal and account/Group isolation. Focused77 and full15,839
pass, retaining all15,797 preceding identities plus42 new executions. Android compilation/lint,
Web, Apple source/test compilation and the proof APK pass. Actual Apple execution of these21
methods remains a separate gate. No live service or phone UI changes are included.

Next: admitted finite-page/reference readers and progress actions; one account/Group-owned command
observer and finite worker; bounded exact-command legacy seeding; incoming history producers; and
admitted product list/Group integration. The list must retain unchanged displayed rows and scroll
when invisible repair metadata changes. Pending outgoing edits and global backward-clock ordering
still need their own explicit integration. Do not infer delivery from a missing queue row or
activate the product graph before its actual producer/consumer and native gates are proven.

Evidence: `docs/measurements/collaboration-space-inbox-repair-checkpoint-2026-11-13.json`.

## Actual Apple and portable verification

Control 43427620202 at 42fd5d12c5353779d4a61cc50b8c53ae8c6c0bc6 passes 19,362 cases, including 5,692 actual
Apple executions and all 101 new repair/archival cases. The host and 16 push fields pass.
Earlier pending Apple status is superseded for this exact source. Later terminal publication,
worker and product integration remain separate. Evidence: `docs/measurements/collaboration-inbox-repair-hosted-2026-11-13.json`.
