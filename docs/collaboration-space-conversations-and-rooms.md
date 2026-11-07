# Organization conversations and room transport

An organization conversation is an immutable pair intent. A separate room status
reports whether the server has published its verified encrypted room. Neither a
directory entry nor a conversation receipt means that a recipient has keys or that
messages may be sent. Original company/conversation coordinates remain separate.

The native transport implements the Group branch contracts in
[conversation identity](https://github.com/fajarxfce/hris-platform/blob/e45a4313/docs/collaboration/space-conversations.md),
[room requests](https://github.com/fajarxfce/hris-platform/blob/e45a4313/docs/collaboration/native-space-room.md)
and [original pair routing](https://github.com/fajarxfce/hris-platform/blob/e45a4313/docs/collaboration/original-pair-routing.md).
It does not enable the disabled Group rollout or introduce a product caller.

## Boundaries

Domain entities and pure structural policies retain the owned unsigned account
pair, immutable creation metadata, independent group ID, publication origin and
request owner. Repository contracts separate conversation discovery/opening from
room request/status. Each implementation coordinates only its raw datasource and
maps acquisition plus response validation inside `safeApiCall`. API services own
bounded HTTP I/O. Koin discovers their registrations in the existing data module;
the application graph must resolve both repository singletons.

Routes use the authenticated native client with its explicit local session and
root `/collaboration/spaces/{spaceId}` path. They add no company authority or cookie.
The owning caller still has to admit the current native parent, client, organization,
participants and device. Transport validation is not that live admission. HTTP denial
classification survives mapping; cancellation is propagated after suspended I/O.

## Finite discovery and immutable requests

Conversation pages accept 1–50 rows, validate every owned pair and require strict
unsigned ID progress. Oversized, duplicate, reversed, foreign and stale pages fail
as a whole. Continuation is either absent or the last ID of a full page. It never
creates a continuation for an exhausted response. These inventory pages are not
recent-chat ordering or Matrix timeline pagination.

Open transmits the exact operation and peer and accepts only a version-zero group
conversation receipt. Room requests transmit the current logical device and observed
device version. An explicit terminal retry retains **both** observed request account
and revision in one typed value. A peer's high revision may lead to the requester's
revision zero: revisions from different participants are not globally ordered.
Room receipts must identify the same group conversation and a bounded revision.

The caller must persist each immutable operation and complete payload before I/O,
and retain an uncertain command after timeout or cancellation. These adapters add
no business retry loop. The shared native client may perform its bounded credential
rotation with the original body and idempotency key. Explicit replay keeps those
same values. Fresh-operation preparation, cooldown policy and durable recovery still
belong in owning use cases and encrypted storage.

The existing Group room operation fingerprint also includes native parent ID/version
and actual platform/build, unlike the direct-open fingerprint. Ordinary native token
rotation increments that version. After an earlier uncertain commit, replay with a
new version/build can therefore conflict despite an unchanged JSON body. Transport
must preserve that conflict and original operation; it cannot report a recovered
receipt or mint a replacement operation. Durable room work must retain the original
proof descriptor, and explicit receipt recovery across changed proof remains a
server/client prerequisite before product delivery. The mocked rotation tests prove
wire preservation, not receipt recovery across an already committed server fingerprint.

## Safe room metadata

Status is either absent, queued/running, or terminal with a matching recorded
outcome. State, owner, account-local revision, attempts, request window, microsecond
timestamps and outcome consistency are validated together. A published room exists
exactly when status is successful. Unknown states/origins/failure codes, swapped
scope/owner or partial success fail without publishing a room.

LEGACY retains its server-returned original room ID and origin. This safe endpoint
does not expose its private provisioning proof, lease token or SDK credentials.
The owning adoption/delivery caller must still compare the published LEGACY binding
with exact original metadata and canonical participants; it cannot substitute a new
room when original history is unbound. A read or retained queue match grants no send,
key-query or content permission.

Responses are bounded to 64 KiB for a conversation page, 4 KiB for one conversation,
8 KiB for room status and 1 KiB for mutation receipts. The shared native HTTP timeout
remains finite (15 seconds by default). There are no background collectors, SDK
handles or timers owned by these transport repositories.

## Acceptance and continuation

Transport tests exercise actual Ktor request/response serialization, rotation and
replay, empty/final pages, opposite participants, mismatched ownership, terminal
status integrity, bounded responses, denial classification and cancelled late results.
[Acceptance](measurements/collaboration-space-conversations-and-rooms-2026-11-07.json):
full gate passed 3,530 fresh cases in 439 suites in 416.326 seconds, with 4,349
unchanged source inputs, no failures/errors/skips and no Android release lint issues.
Focused final app graph/transport gate passed 20 cases in 106.323 seconds. The first
gate passed 18 transport cases but exposed an old app test runtime missing the new
original-writer method; its explicit no-implicit-write implementation is corrected.
The final full gate also covers a changed-proof replay conflict and validates the
same common transport on Android. Existing checks that reused XML remain identified
separately; this is not an actual provider or phone latency measurement.

Next retain encrypted account-owned open/room commands and their exact uncertain
outcomes, then implement finite owning preparation/delivery. Only after current
recipient admission and published-room validation may the restricted original writer
resume the complete matched SDK queue. Receipt/history publication, native-parent
recovery and product routing remain separate work. This HRIS-specific transport
has no reusable Starter change and does not require a phone installation by itself.
