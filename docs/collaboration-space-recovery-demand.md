# Skip original-room transport only after local work is complete

The organization recovery worker now checks local demand before per-room delivery.
An empty SDK queue alone is insufficient: a retained command or receipt may still
need its Sent projection, and a confirmed app message may still exist in the native
queue awaiting settlement. Only a complete local SDK inventory plus bounded app
commands, acknowledgement journal and immutable completion evidence can establish
an idle room. Idle completion changes recovery progress, never message state.

ReadCollaborationSpaceRecoveryDemand owns that read. It validates the exact retained
original/group room mapping, current local native family and directory denial. It
uses the already adopted runtime's local inspection port, never creates a room or
SDK identity, and never calls transport, enqueue, resume, settlement or history
retirement. Its five-second owner bounds cancellation and stalled I/O. Collections
are detached before suspending, and SDK plus app inventories are rechecked before
returning idle. A changed inventory remains work. A local failure retains recovery
through the worker's existing failure/backoff path.

The existing delivery use case retains all current remote permission, readiness and
original-only writer rules. The worker still owns original adoption and sync once
per turn; this change skips only unnecessary per-room transport. It does not claim
that an entire idle application makes no network requests.

The native proof requests a fresh durable wake after both original messages are Sent,
then invokes the actual registered group task. The server test requires completion
of that idle sweep with no additional direct transport request. Existing original
ACK/process-loss/transaction/two-provider-event/offline assertions remain included.
The fixture scheduler is explicit; this is not an OS scheduling or phone-latency test.

The first focused candidate had one test assertion error: its eight raw pages
needed two four-page turns. The corrected assertion preserves the bound and confirms
the wake remains after the first turn; no production behavior was relaxed.

Producer wakes for work committed after the last read remain required. Group product
routing, new SPACE enqueue, group FCM and native Apple runtime acceptance are separate.
No existing encrypted messages are rebucketed, keys replaced or originals resent.
Phone87 and the live group-disabled server remain unchanged by this source increment.

## Accepted validation — 10 November 2026, 00:25 UTC

Focused validation passes123 cases in170.44 seconds:40 domain (16 new demand cases
and24 unchanged original-writer cases),63 application cases,and20 proof unit cases.
The required full gate passes4,437 fresh cases in483.891 seconds,with20 byte-identical
proof results reused from the focused gate;4,457 validated in total. No failures,
errors or skips. Root checks,Android host/devRelease compilation/lint,shared Wasm/iOS
source,16 browser demand cases,proof packaging and format/architecture/detekt pass.
Both4,633-input manifests and every current source/library/XML hash match.

Backend test compilation,format and architecture pass30.759 seconds with8,733 stable
inputs. Only one backend test changes. Actual native validation passes2 cases in
203.279 seconds,zero failures/errors/skips. The original scenario
retains2 provider events/10 native requests. Group recovery retains2 provider events,
24 native and16 Matrix requests,
zero401/403/server errors. Exactly2 direct transport reads serve the interrupted and
recovered original work;the explicit fresh idle sweep adds zero direct transport reads.
All original transaction,ACK-before-Sent/process-loss/dedup/offline assertions pass.

The isolated2,560MiB/two-core emulator is stopped. No compilation ran during it;
all source/APK hashes match. Proof APK150450270 bytes,SHA256
`2d3e323b055123cbbfc3e17bbf1b375d59291be31994089fa19270434129115a`. SDK6 x86_64 library remains
`ecffbab72fd5dd29d656bc4757ea423bb4bccf0444140af0480684bc32761278`.

[Audited measurement](measurements/collaboration-space-recovery-demand-2026-11-10.json).
