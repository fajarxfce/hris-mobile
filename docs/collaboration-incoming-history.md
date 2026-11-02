# Independent incoming history

`CollaborationHistoryPurpose` binds each traversal to Browse or Incoming. Both the
explicit selected and background acquisition APIs carry it through live room/device
admission, provider reads, the atomic reference commit and subsequent local reference
reads/reconciliation. Defaults remain Browse. Original content remains shared by
account/company/conversation; a separate traversal never implies a second message.

Browse retains the exact existing `/pages/<device>` partition, schema1 payload and
`hris-history-pages-v1` cryptographic context. Incoming uses `/incoming-pages/<device>`
and `hris-incoming-history-pages-v1`. The context binds both payload encryption and
keyed reference indexes. The shared content/index key guard includes both partitions,
including inactive accounts. There is no migration, cache reset or raw caller-provided
namespace. An absent incoming checkpoint never falls back to the Browse cursor.

Existing atomic page, duplicate/conflict, generation/revision, original/tombstone
reconciliation, active-owner and bounded-query contracts apply separately to each
purpose. Restarting or resolving Incoming cannot update Browse. Simultaneous writes
to different purposes can both commit; stale writes to the same purpose still lose.
Captured local reads remain independent of selection and credential freshness, and
confirmed denials retain priority. See [background history](collaboration-background-history.md).

[Validation evidence](measurements/chat-incoming-history-purpose-2026-11-02.json)
records3,144 desktop,216 Android host,886 affected browser and10 build-logic cases
plus Android release compilation. All pass on16 unchanged source inputs.

Validation covers a manually encoded legacy Browse checkpoint, including exact old
AAD; copying the same generation/ciphertext to Incoming fails authentication. Other
shared SQLite/IndexedDB cases cover duplicate identities, interleaved restart and
resolution, ciphertext transplantation and simultaneous independent commits. The
existing eleven storage scenarios also run against Incoming, including cancellation,
lost response, key loss, account replacement, copied rows and bounded paging beyond
outbox capacity. Four domain cases exercise explicit purpose with selected/background
acquisition, cancellation, denial and captured offline reconciliation.

## Next integration

The incoming app worker still acquires a latest original window. This change provides
isolated traversal storage; it does not yet wire long-offline catchup. The next worker
increment must retain an unfinished scan across wakes and a fair pending-reference
position. The gateway already accepts opaque Matrix `from`/`to` positions; provider-bounded
waves are the preferred next acquisition contract, rather than timestamp/overlap
heuristics. Only metadata coverage may advance a completed wave boundary;
plaintext availability and original receipts remain independent. Missing keys and
state-only references must not indefinitely hide later references. Never use ordinary
event timestamps as provider order or restart Browse on a background wake.

Platform direct rooms are non-federated (validated `m.federate=false`), and the current
provider is private Synapse. The next provider fixture exercises more than one page within a saved `to`
boundary, new sends during a partial wave, adapter recreation and an empty caught-up
wave. The worker must preserve both bounds across wakes; invalid/retired positions
require an explicit recovery decision, with all local references retained. Validate gaps larger than the SDK's original window with
actual provider/Android execution. Native client lifetime retains at most16 distinct
history focuses; the worker must preserve foreground ownership and durable progress
when that budget is unavailable. Live push/notification/reply acceptance remains open.

The shared-key guard also rereads credentials once when retained ciphertext appears
after an initially missing key. A competing repository may have committed both in
that interval. It reuses the existing key without replacing it; key loss still fails
closed. The deterministic pre-fix failure and full corrected SQLite/IndexedDB checks
are preserved with the original unrelated-looking concurrent-progress test failure.
