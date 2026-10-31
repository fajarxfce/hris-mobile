# Native conversation control

The collaboration data module now exposes bounded native adapters for participant
selection, DM list/detail/open, and direct-room status/setup. The application DI
registers the adapters and `ReadCollaborationConversations`. Its explicit reads
capture current company/native ownership before I/O and reacquire admission after
both successful and failed responses. Permission loss and session/selection changes
win over obsolete content or a transport failure. Cancellation propagates. Reads
never start enrollment, provisioning, a native client, or an automatic retry loop.

The selector forwards literal search text (up to 80 UTF-16 units), a canonical UUID
cursor and a limit from 1 to 50. Returned pages must be bounded, unique, ordered and
advance their cursor to the final row. Account/company/conversation substitution
and incoherent room states fail before publication. Names are selection hints;
server-side eligibility is checked again for commands. Existing DM identities may
remain readable after the peer leaves; this metadata is separate from fresh
communication admission and an eventual owned offline projection.

Conversation and provisioning mutations preserve the caller's operation UUID and
observed versions through ordinary native token refresh. A room request carries
explicit null for initial setup or the original provisioning version for retry.
A direct-open receipt identifies a DM, while room receipts identify its setup
revision. Transport does not generate or replace operations, schedule retries,
or compensate with deletion. **These mutation ports are not yet connected to a
product action; durable intent preparation/replay/reconciliation must precede UI
submission.** A network failure cannot establish that an earlier operation did
not commit.

Participant pages are capped at 128 KiB, DM pages at 64 KiB, individual metadata at
2 KiB and mutation receipts at 1 KiB. Acquisition and mapping share the existing
safe API boundary. No response bodies, credentials or participant names are put
in error diagnostics. HTTP admission failures preserve their safe server code.

Room READY means verified provider provisioning only. Recipient enrollment/keys,
current room authorization, SDK sync, message durability and delivery are separate.
Version-12 opaque Matrix room IDs do not require a server-name suffix. Terminal
retry eligibility includes the original creator and server retry time; expiration
of that waiting time does not discard the retained state.

Continue durable conversation commands, SDK sync/room children under application
shutdown, encrypted owned projections and chat UI. This control layer does not
complete CHAT-01 or any P0–P8 product group. Apple execution is checked separately
in hosted CI; browser adapter tests do not enable browser-native admission.
