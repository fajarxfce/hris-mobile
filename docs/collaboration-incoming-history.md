# Independent incoming history

`CollaborationHistoryPurpose` binds each traversal to Browse or Incoming. Both the
selected and background acquisition APIs carry it through current room/device
admission, provider reads, atomic reference commits and local reconciliation.
Defaults remain Browse. Original content stays shared by account/company/conversation;
a separate traversal never implies a second message or an outgoing command.

Browse retains the existing `/pages/<device>` partition, schema 1 and
`hris-history-pages-v1` cryptographic context. Incoming uses `/incoming-pages/<device>`
and `hris-incoming-history-pages-v1`, binding encrypted payloads and keyed indexes.
The shared content/index key guard covers inactive accounts and both partitions.
An absent Incoming checkpoint never falls back to Browse. Simultaneous writes to
different purposes may both commit; stale writes within one purpose still lose.

## Provider-bounded waves

Each incoming turn acquires the latest SDK original window, one metadata page of at
most 20 references, and at most two anchored original windows. The enclosing worker
owns the runtime, timeline, access monitors, single-turn mutex and existing deadlines:
12 seconds per room within a 15-second incoming lane. No selected workspace is read.

A wave retains its first provider `start`, the previous completed head as exclusive
`to`, and the provider `next` as `from`. Partial wakes and explicit partial restarts
keep the same older bound. Only exhaustion promotes a head into the next wave's
bound. Messages arriving during a partial wave belong to the subsequent wave; no
ordinary event timestamp is used as provider order. Returned request boundaries must
match the captured progress before metadata can commit. Invalid positions preserve
local facts and fail; automatic cursor retirement/reset is not implemented.

The gateway already supports company/account/device/server/room-bound encrypted
`from` and `to`. Platform's actual Synapse boundary test verifies 47 messages within
a saved bound, five concurrent sends in the next wave, adapter recreation and an
empty caught-up wave. Those payloads are synthetic encrypted metadata; SDK plaintext
acceptance is a separate Android fixture.

## Fair unresolved references

The encrypted checkpoint also retains the pending-reference seek, fixed reference
high-water count and a retry flag. One pass cannot grow indefinitely when new pages
append references. A turn reconciles known originals/tombstones, attempts the first
and last potential message anchors in a bounded pending page, reconciles again and
commits only seek progress. This does not acknowledge or delete unresolved content.

A missing key cannot block visiting later references, and retry uncertainty survives
until the pass ends. The cursor then wraps for another finite pass. Metadata coverage
may advance independently of plaintext availability; Complete requires both provider
exhaustion and a pending pass without unresolved message content or retained retry
uncertainty. State-only references remain retained without forcing a plaintext retry.

Seek commits tolerate concurrent reference resolution/page extension only in the
same generation with unchanged incoming wave/seek state and monotonic revision/count.
Every captured reference is authenticated against its retained identity and sequence.
A competing seek loses; failed, cancelled and late commits preserve owned facts.
Captured local reads still distinguish ownership from credential freshness, with
confirmed denials taking priority.

## Storage and lifecycle

Schema 2 encodes ASCII provider positions canonically inside the authenticated
payload to bound JSON expansion. Incoming plaintext is capped at 24,000 bytes;
all encoded envelopes retain the database's existing 32,768-character checkpoint
limit. Legacy Incoming schema 1 resumes conservatively from its saved next position.
Browse's legacy bytes/AAD remain unchanged. No generic database bound is enlarged.

The native SDK retains at most 16 history focuses per client. A background-owned
client is drained after its final lease; shared foreground ownership is preserved.
Capacity contention produces a retained retry rather than closing another owner's
runtime or forgetting a reference. No unbounded queue, retry loop or native cache is
introduced by the coordinator.

The shared-key guard also rereads credentials once when ciphertext appears after an
initially missing key. A competing repository may have committed both. Existing keys
are reused; missing or malformed retained keys still fail without creating a key over
old records. Its deterministic pre-fix race and legacy/cross-purpose tests remain in
[isolation evidence](measurements/chat-incoming-history-purpose-2026-11-02.json).

## Validation

Focused checks pass 584 domain, 346 data and 94 selected app cases, Android release
and proof compilation plus format/architecture/detekt on 47 unchanged Kotlin inputs.
The new actual app/DI/SQLite cases cover 175 messages with a fictional 21-item SDK
window, withheld keys plus later sends, and cancellation after the metadata commit.
Shared SQLite/IndexedDB contracts exercise fixed high-water passes, competing seek,
forged anchors, legacy incoming bytes, account change and worst-case opaque bounds.

Full root, affected browser, Android release/proof and app-Wasm checks pass in
428.941 seconds on 51 unchanged Kotlin inputs: 3,169 desktop, 217 Android host,
908 collaboration browser, six proof host and ten build-logic cases; zero failures,
errors or skips. The real SDK/provider Android fixture also passes all three variants on the same
APK and unchanged52 Mobile/7 Platform inputs; see
[wave evidence](measurements/chat-incoming-history-waves-2026-11-02.json).
The Android fixture verifies 175 encrypted peer messages while the receiver runtime
is closed, then reopens its app container after every bounded incoming turn. It checks
all originals, retained traversal, empty outbox, no selection, unchanged Browse and
released native resources before foreground paging. Container reopen alone is not
Android process death; the existing outgoing death scenarios remain separate.

QUEUED, FROZEN_DRAFT and CANCELLED_DRAFT each retain176 originals, exhaust186
provider references in11 incoming turns, and survive11 container reopens. Foreground
paging then completes11 independent Browse pages with10 pending state-only references.
The server verifies exactly176 encrypted sends, one from the outgoing worker. Native
runs2+3 pass one and two cases in394.103s and671.255s, respectively, with all cleanup
successful. The two pre-existing SDK/OS-scheduling regressions passed in native1.

The initial expanded fixture exceeded its old210s limit twice; the remaining case
failed before installation on an empty AVD reply. Bounded phase/turn diagnostics show
successful fixture completion takes245.182–260.457s, including175 deliberate queue
pause checks and repeated container reopens. The worker-resume fixture now allows420s;
production room/lane deadlines are unchanged. The driver rejects empty AVD replies
before mutation, and84 Python cases pass. Original failures and the unexplained exit
of the first owned emulator remain in evidence. This is not a production latency or
capacity benchmark.

Live FCM, notification privacy/actions and encrypted replies still require acceptance.
All communications PRD groups remain open. This HRIS-specific traversal has no generic
Starter port. See [background history](collaboration-background-history.md).
