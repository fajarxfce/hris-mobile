# Restoring the end of older messages

Closing and reopening a room must not advertise older messages after its retained
provider traversal is exhausted. Matrix room-state references remain durable even
though they do not produce canonical message originals. Previously the in-memory
scan hid the control, but restoration required *every* reference to be resolved;
retained membership/creation events made the control reappear.

The screen-owned pager now reads bounded local pages until it finds a possible
message or reaches the end of the original finite snapshot. Encrypted messages,
ordinary messages and stickers keep older history available. Other references
remain retained and unresolved; no fabricated original, acknowledgement, migration,
provider read or decryption attempt is needed to restore the end. Existing installs
benefit from their already-stored traversal metadata.

Each page validates current local ownership/admission. The walk requires increasing
reference sequence, unchanged generation/reference count/provider position, and a
committed provider end. Concurrent replacement/growth leaves history available;
resolution-only revisions can proceed. Cancellation propagates and the owning
ViewModel releases its timeline. Canonical local-history pagination still controls
whether older cached messages are available independently of this provider end.

Common ViewModel regression covers empty state-only rooms, more than two metadata
pages across repeated room recreation, a real pending encrypted message behind
metadata, concurrent growth/replacement and cancellation. The actual release34
receiver also reproduces the disappearing/reappearing control with its retained
room. [Measurement](measurements/chat-history-end-2026-11-05.json).

This is HRIS communications policy; no Starter foundation change is required.
Forwarding/atomic batch work remains separate and must return its reusable storage
changes to Starter after validation.
