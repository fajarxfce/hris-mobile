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
or compensate with deletion. **Durable preparation/replay/reconciliation is now implemented below;
product navigation and submission remain pending.** A network failure cannot establish that an earlier operation did
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

Continue product room/timeline children under application shutdown, encrypted
owned projections and chat UI. This control layer does not
complete CHAT-01 or any P0–P8 product group. Apple execution is checked separately
in hosted CI; browser adapter tests do not enable browser-native admission.

## Durable native control commands

`PrepareCollaborationConversation` captures live native/company admission and
retains one immutable open-DM intent per peer or room-setup intent per conversation.
Room preparation observes the active registered device and room status; an explicit
retry requires the creator, original version and elapsed server waiting time.
Preparation reads back the winner after competing insertions and never sends it.
Stored commands remain independent of ordinary age and local-session replacement.

The separate command partition uses the existing bounded `CommandStore` and shared
protected content key. Request and outcome envelopes authenticate stable owner,
operation, resource, purpose and revision independently. Missing/wrong keys cannot
replace retained ciphertext. Compare-and-set updates/removal require the exact
observed command record; another revision or changed input cannot be overwritten.

`ReplayCollaborationConversation` serializes explicit attempts, stores uncertainty
before HTTP and preserves the operation/action after cancellation or lost responses.
These control payloads do not bind the app build, so ordinary upgrades can retry the
original command under current client admission. Gateway bootstrap retains its
separate build constraint. At most 1,000 explicit attempts are allowed; no automatic
retry or deletion compensation runs here. Known server authorization/access denial
remains in the outcome across later failed transport retries, including failed
outcome persistence. The same precedence now applies to setup replay. A confirmed
successful response can replace the retained denial.

`ReconcileCollaborationConversation` observes the owned DM's expected participants
or the current room provisioning version before retiring a confirmed receipt.
Setup receipt reconciliation does not claim that a room is ready or that recipient
keys exist. Unconfirmed intents and failed/foreign observations remain retained.
Current ownership/admission is reacquired after every pending storage or HTTP step;
late results and cancellation cannot replace another selection's state.

[Validation](measurements/collaboration-conversation-commands-2026-10-31.json)
records real SQLite/AES-GCM reopening, common browser codecs, competing writes,
lost responses, original-operation retries, scope/permission changes, failed cleanup
and safe receipt retirement. This is control preparation; offline message composition,
product pending-action UI and cross-family recovery are separate remaining work.

## Read failure precedence

Setup, participant search, conversation list/detail and room-state reads retain
received Unauthorized/AccessDenied when a following session read fails with a
transport/storage error. A newer authorization denial still takes precedence;
there is no refresh, provisioning or retry side effect. Existing cancellation
and scope-replacement guards remain in place. See [verification](measurements/collaboration-read-denial-2026-11-01.json).

The same explicit received-result guard now covers preparation, replay and receipt
reconciliation. Retry also preserves retained denial when writing uncertainty
fails before transport, and received denial across outcome-write/read failures.
[Control verification](measurements/collaboration-control-denial-2026-11-01.json)
also exercises install/restore credential denials with failed owner reads and
failed lease cleanup. No automatic replay or metadata retirement is introduced.
