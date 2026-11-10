# New Space-room queue provenance

An organization conversation can use a newly published SPACE room without any original
company conversation. Its logical commands belong to the Space partition. The installed
SDK may still be the adopted original device and retain its original physical account,
company binding, bootstrap operation and Matrix device ID.

The mixed queue scope policy now distinguishes these facts explicitly. LEGACY room
captures continue through the existing original provenance policy and two command ledgers.
SPACE room captures validate the canonical binding, exact runtime/parent/device/room,
original sender and bounded unique logical/transaction inventory; their company command
access is absent. No original company conversation is manufactured from a Space ID.

The typed matcher rejects company commands or completion proofs in a SPACE room. It still
requires an exact Space record and immutable proof for every captured Sent original. The
local reader consults only the Space repository for a new room, rechecks admission after
I/O, then rechecks the retained binding, complete native inventory and installed runtime.
Missing records and failed reads remain unresolved; they do not trigger company fallback
or remote discovery. Existing ownership and offline-read rules are preserved.

A separate pure transport match compares current public facts with the same captured
physical sender/device for either room origin. Matching alone does not grant delivery or
recipient readiness. Owning staging/delivery usecases must admit those separately.

Thirteen regression cases cover a new physical room, empty capture, exact Sent proof,
malformed/foreign/duplicate/oversized inventory, forged legacy metadata, wrong transport,
company namespace collisions, read failures, late denials and changing binding/inventory.
The existing 34 mixed matcher/reader cases remain in the focused gate. No new native
acquisition, room joining, enqueue, resume or UI route is activated by this increment.

[Local acceptance](measurements/collaboration-space-new-room-queue-2026-11-10.json)
records completed checks. Actual native staging, mixed delivery and pending-command
inventory remain the next steps; Apple Matrix runtime implementation remains required.
