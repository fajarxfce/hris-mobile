# Incoming sync retention

Status: storage and existing scheduling owners validated; application batch production is not enabled.
This extends the [incoming scheduler](collaboration-space-incoming-scheduling.md) with
stable source progress and unresolved physical rooms. Raw native batches are described in
[raw sync batches](collaboration-raw-sync-batches.md).

## Storage and ownership

The existing account/space incoming partition owns the complete demand snapshot. Source
progress records retain the original SDK store SHA-256 identity, Matrix user/device, exact opaque
position and an optional outstanding provider-inventory generation. No access token or
short-lived parent session is stored in this record. Room hints carry their source reference;
a Matrix locator alone grants no room binding, read permission or sending capability.

Logical and physical room work share 64 FIFO positions and unique work generations. Up to
64 distinct original source stores can remain referenced. Existing logical phases, retries,
local-binding scan progress and wake identity survive unrelated source updates. Removing a
source with referenced room work or unresolved inventory requires an explicit owning policy;
this storage increment does not implement eviction.

A complete replacement and its optional incoming wake use the same existing ReplicaStore
transaction and exact observed-snapshot comparison. Cursor-only replacements can omit the
wake without creating or changing one. An uncertain write response is not a reason for the
repository to reissue the transaction. The owning use case confirms retained facts by reading
them back. Cancellation and account replacement retain their existing storage fences.

## Bounded authenticated format

Legacy schema 1/2 still uses the `incoming-demand/v1` checkpoint and one `demand` document.
Source-bearing snapshots use `incoming-demand/v3` with these authenticated parts:

| Part | Contents | Maximum |
| --- | --- | --- |
| `demand` | Schema, exact part counts, FIFO frontier, independent local-binding scan | 1 |
| `r-00` through `r-63` | One logical room, phase, retry and optional source reference | 64 combined with physical rooms |
| `p-00` through `p-63` | One unresolved physical room and source reference | 64 combined with logical rooms |
| `s-00` through `s-63` | One original source and its progress | 64 |

Each ciphertext authenticates the exact account/space, snapshot generation and document ID.
The header authenticates the complete expected ID set. The repository requests 130 documents:
129 is the maximum valid snapshot, and the extra entry detects overflow. Missing, additional,
duplicate, substituted or foreign-generation parts cannot decode as a complete queue.

Every part remains within 65,536 plaintext bytes and 131,072 ciphertext characters. The whole
snapshot stays within the raw store's existing 200 documents and 4,194,304 ciphertext characters.
Opaque cursor values are strict UTF-8, at most 4,096 bytes, represented as Base64 inside encrypted
JSON so control-character escaping cannot exceed the part limit. Domain validates structural
bounds; the data codec owns wire encoding and authenticated payload bounds. Core storage and
Starter limits are unchanged.

## Existing scheduling owners

The pure `captureCollaborationSpaceIncomingDemandSnapshot` policy gives use cases a bounded
copy of the logical-room, source and physical-room lists before later suspension. Current
admission and structural validation stay in the use cases.
Local reads report unfinished physical work or provider inventory as pending. Startup and warm
resume schedule retained work without replacing it. Local-binding discovery uses only the
remaining combined room capacity; completing that scan cannot clear provider-inventory loss.
Wake completion requires all logical rooms, physical rooms, provider inventory and local scans
to be idle. Plain cursor progress is not pending work.

## Remaining integration

The serial admitted batch producer, exact runtime/source observation, physical-room resolution,
provider inventory traversal and stored-key subscription still need implementation. The current
worker cannot consume the new physical/inventory work, so application production remains disabled.
The Group physical-room read covers published Space bindings; unpublished Company originals need
separate routing. No new sync client, transport polling, phone performance result or PRD acceptance
is claimed by this storage format.

[Validation evidence](measurements/collaboration-space-incoming-retention-2026-11-11.json)
records 552 focused SQLite/browser cases and 11,061 full cases: 9,023 fresh plus 2,038 exact
accepted controls with unchanged source/dependency inputs. Every prior case ID is preserved.
Format, architecture, detekt, Android host/release compile/lint, browser/Wasm and iOS metadata
checks pass on the same 4,872-input candidate. Native Apple execution of this new storage format,
application producer/worker integration and phone performance remain separate validation.

The initial source-ID model was rejected after tracing the original protected store's full
SHA-256 identity. No application producer or real source data was released. Subsequent review
centralized bounded snapshot capture and separated typed DTO mapping; lint limits were unchanged.
Private evidence preserves failed and superseded candidates alongside the accepted result.
