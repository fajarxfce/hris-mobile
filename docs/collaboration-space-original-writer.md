# Original message writer under organization ownership

`CollaborationSpaceOriginalQueueLease` is a restricted handle for one existing
physical queue under an exact `CollaborationSpaceRuntimeReady`. It exposes complete
paused snapshots, settlement with caller-owned immutable completion proof, whole
captured-inventory resume, and close/drain. It exposes no legacy owner, text preparation,
enqueue, store creation, joining or synchronization. A returned handle grants neither
server admission nor permission to send an unpublished organization room.

Android acquires the existing runtime's single exclusive writer through its exact
logical owner/device/bootstrap registry entry. It owns the acquired child before checking
cancellation, credential failure and resource replacement. A failed acquisition closes
the child without replacing its primary failure; an unfinished close retains the
original writer until the parent drains it. The handle rejects operations after close
or logical-runtime loss, with no queue backlog between competing callers.

Each resume captures the caller's bounded original list before suspension, then reads
complete IDs/content/IDs while holding the writer. Runtime, original binding/device and
every original transaction/content must match. Missing, extra, duplicate, changed and
oversized inventories fail before native resume; no partial set can be sent. The
underlying SDK also checks its captured transaction set before enabling the room.
Read or operation failure remains primary if logical ownership changes meanwhile.

The native operation owns its captured receipt callback after waiter cancellation.
Logical UI revocation must not discard a genuine late acknowledgement: the future
owning use case must journal only captured original facts and independently gate current
projection. Close pauses and drains that callback before releasing the physical writer.
Settlement reports `AlreadyAbsent` as absence, never an invented receipt or success.
The caller must supply exact immutable completion evidence before settlement.

## Original device identity correction

The actual SDK adoption fixture retains a physical device ID different from the new
organization device ID. The earlier pure matcher incorrectly required equality. The
new regression fixture reproduces five failing valid scenarios before the correction.
Original native family, device and bootstrap remain unchanged and are validated against
every immutable command. The logical device/bootstrap remain independently pinned by
the runtime registry and current admission. Neither ID is rewritten to the other.
Changing a snapshot's original device without matching original commands still fails.

`UnavailableCollaborationRuntimeRepository` explicitly rejects the new capability.
The changes are HRIS-specific; no generic Starter port is needed. Domain remains pure;
the use case coordinates admission/command/completion policy and the Android resource
owns SDK I/O/lifetime. There is still no product caller for original queue delivery.

## Validation — 7 November 2026

The device-alias regression first failed five of ten cases in75.293s. After correction,
focused domain/Android tests passed41cases in131.880s, with format/architecture/Detekt.
Full root/Android/proof validation passed3,512fresh cases in441suites in438.471s on4,328
unchanged inputs, with no failures/errors/skips or lint errors;49 existing proof lint
warnings. Twelve synthetic-seed validator cases passed.

Actual immutable SDK4 passed x86_64 and ARM64 in154.702s including fresh emulator boot.
Both runs preserve two original queued messages and private keys across interrupted
adoption, real process death, offline restoration and repeated bootstrap. They verify
the held writer's exact namespace, unpublished/foreign rejection, competing-writer
exclusion, rejected partial/changed resume, closing and renewed complete reads.
Both fixtures were removed and the emulator process/transport stopped. No provider
message, user-phone action, original Bima mutation, SDK rebuild or live group activation.
[Complete source/hash-bound measurement](measurements/collaboration-space-original-writer-2026-11-07.json).

## Next integration

Mobile still needs group direct-conversation/open and room request/status transport,
durable commands and the owning original-delivery use case. Opening an immutable group
pair does not replace original company/conversation coordinates. Room retry uses the
observed account-plus-revision tuple; it is not one globally sortable revision.
LEGACY publication must preserve the exact original room and V132 canonical pair.

The delivery use case must coordinate fresh directory/native/device/identity/recipient/
published-room admission with complete held-queue matching and immutable command,
receipt and completion repositories directly. It must not call another use case.
Original history access and a local match alone grant no sending authority. V117–137
remain disabled/unshipped, and physical phone53 latency remains open. Continue original
history publication, native-parent recovery, product routing and all54 PRD groups.
