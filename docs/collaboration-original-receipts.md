# Recovering acknowledgements from original history

An SDK acknowledgement can arrive just before process death, leaving an immutable
Staged application command and no receipt journal row. An empty native queue does
not prove delivery. Recovery requires an acquired, decrypted original with the
exact room, sender, logical ID and full canonical content identity retained before
enqueue.

## Before enqueue

The Android writer derives `CollaborationMessageOrigin` from its protected Matrix
identity and the complete outgoing text content. `StageCollaborationText` persists
that origin with the Staging snapshot before calling the writer. Enqueue verifies
it again before any SDK mutation. The origin remains unchanged through Staged and
Sent, including receipt-driven projection and reopening the encrypted command store.

Text uses the pinned SDK's explicit plain-text constructor, with literal body,
`msgtype: m.text`, empty `m.mentions`, and `org.hris.message_id`. Markdown, HTML,
whitespace, emoji and newlines in this submission remain literal body text. Rich
formatting is a future explicit content type. The former Markdown factory could
add formatted fields and therefore could not establish this payload identity from
body alone. Preparation and original decoding share the same canonical SHA-256
function: object keys are sorted, array order and every unknown content field are
significant. The original decoder keeps its existing JSON size/depth bounds.

Old envelopes with neither origin field remain readable. Partial or malformed
origin metadata fails validation. A legacy Staging command with no native mapping
stays unresolved; recovery does not invent an origin or automatically resend it.
A known old native mapping remains readable without acquiring a new identity.

## Local recovery and publication

`RecoverCollaborationMessageReceipts` reads the owned conversation journal and up
to 128 retained commands. It skips known receipts, non-Staged work and legacy work
without an origin. Each remaining candidate uses the encrypted history's indexed,
room-bound sender/logical-ID lookup. Both the full content hash and unredacted body
must match. A confirmed redaction may retain the previously authenticated content
hash with no body; all observed event aliases remain attached to the same receipt.
A missing original stays pending. Conflicting or malformed evidence rejects the
entire prepared batch before append.

At most 32 receipts are appended once, under the journal's atomic checkpoint CAS.
The 128-message/512-alias ledger bound still applies. Unknown candidates at the
front do not prevent later matching commands from progressing. A lost append
response leaves the committed journal available for an explicit subsequent call;
there is no automatic retry or native SDK/network access in this operation.

The operation rechecks current local owner and company admission after every
suspension. Ordinary authority age does not hide owned facts; confirmed denial or
owner replacement prevents publication. A late denial after append retains the
encrypted receipt. Command state is unchanged here. The separate existing
`ReconcileCollaborationMessages` operation projects exact journal mappings to Sent.
Neither operation enrolls an identity, resumes a queue, clears denial or fetches
history implicitly. Admitted history acquisition remains a separate action.

## Validation scope

Common-domain tests cover exact repair, aliases/redaction, missing and conflicting
originals, malformed states, batch atomicity, cancellation, owner/denial changes,
lost writes, bounded progress and capacity. SQLite/IndexedDB storage tests retain
the origin across reopening and reject attempted identity changes. Android writer
tests reject mismatched payload identity before any enqueue.

The dedicated Android/HRIS proof additionally exercises a process death after the
native acknowledgement and before its journal write. Its checkpoint carries only
the retained transaction, PID and verified interruption flags, never the ACK event
ID or body. Recovery must obtain the original independently. The driver verifies
three distinct processes across staging and ACK deaths; the server verifies three
encrypted sends for the initial message, reply and separate ACK test message.
[Executed evidence](measurements/collaboration-original-receipts-2026-11-01.json)
records the first-attempt pass: 41.453s native case, exactly three encrypted sends,
22/22/22 admissions and cleanup complete. Focused checks, full root/browser/Android/
Wasm builds and lint pass; the Git handoff records their scope and counts. This is
fixture acceptance for the recovery boundary, not full product acceptance.

Command retirement, UI scheduling, Apple/web adapters and full product acceptance
remain outstanding. No generic Starter change is included in this HRIS operation.
