# Encrypted notification publication journal

The collaboration publication repository retains one encrypted notification record per
account/company/conversation. It is raw storage, not permission to display chat or
execute a native action. The application must obtain current native-family ownership,
company admission and current source/settings/read facts before using a record.

A record contains a stable native card ID, a render ID, its original event/hash/timestamp,
and a revision. Prepared and Published records contain the bounded preview and four
opaque action envelopes (reply, mark read, mute, dismiss). Published means the native
adapter acknowledged submission; it does not mean that the user saw or read the message.
Dismissed and Cancelled records retain only a source watermark and target identity.
They contain neither preview text nor action tokens.

Dismissing is an additional durable cleanup intent, also without previews or action
tokens. It stays in the bounded index until exact native cleanup finishes Dismissed;
the issuer cannot republish its original event. The general recovery page includes
it, while Prepared-only discovery does not. This keeps interrupted user dismissals
recoverable without treating them as displayable records. See
[action disposition](collaboration-notification-action-disposition.md).

## Atomic updates and recovery

An account partition under `collaboration-history/notification-publications` contains
an encrypted active index and encrypted per-conversation documents. An HMAC document
ID binds the audience, account, company and conversation without putting these IDs in
plaintext document metadata. One database transaction compares the exact checkpoint,
updates the document and replaces the active index. There is no two-partition index
update window. Unrelated conversation updates do not invalidate an unchanged expected
document; the repository rereads the current checkpoint for each operation.

The pending index permits at most64 Prepared, Published or Dismissing records. Capacity exhaustion rejects a new active
record without evicting another conversation. Inactive watermarks remain individually
addressable but do not consume active capacity. Discovery pages contain at most four
active records, ordered by opaque document ID, and default to Prepared records only.
This discovery order is not message chronology. Watermarks are per-conversation and
are not included in the active page.

Replacing a record requires its exact prior snapshot. An acknowledgment or dismissal
for an older render cannot overwrite a newer one. A successful commit followed by a
lost response remains readable with its original envelopes. Cancellation propagates
and joins storage work; it can withhold the result after a commit without undoing that
commit. The raw repository does not retry, reseal action tokens or infer success from
an absent record. Domain and application coordinators own those decisions.

## Stored format and ownership

The journal uses existing collaboration history content/index credentials with
`create=false`. It never creates replacement credentials over missing keys. There is
no database schema migration or extra key. Same-account reauthentication may read
retained records through the new local session; reading an old native-family record
does not authorize that family or its actions. Database account ownership and exact
checkpoint rechecks reject late account replacement before returning private content.

The checkpoint has a stable UUID generation and monotonically increasing JavaScript
safe integer revision. Active references authenticate document IDs, revisions and state.
Each document authenticates its generation, ID, revision and sort key, and the decoder
recomputes its HMAC ID. Missing active documents, malformed references, ciphertext
changes and cross-account substitution fail the operation instead of returning a
partial active page. Plaintext checkpoint/document limits are 16/32 KiB; encoded
limits are 24/48 KiB. Temporary plaintext and key arrays are cleared after use.

Previews retain only the selected Hidden, SenderOnly or Message fields, with sender
and body limits of 160 and 400 UTF-16 code units. The same control-character and
surrogate rules as notification projection apply. Action tokens must retain canonical
URL Base64 encoding, including required padding. This is format validation only:
the current native-family action repository must authenticate each token before use.
Neither tokens nor previews are copied to generic plaintext notification history.

## Validation and integration limits

[Recorded checks](measurements/chat-notification-publication-2026-11-02.json) distinguish
failed attempts from accepted runs. Fourteen shared contract cases exercise actual
SQLite and IndexedDB storage, reopen, exact CAS, capacity release, cancellation after
commit, uncertain responses, privacy bounds, tampering and account/key replacement.
They use the actual action issuer to retain padded envelopes. Fixture credentials are
kept in memory across database reopen; these cases do not prove OS process-death input
handling. The application graph resolves the new raw repository as a singleton.

Current source facts provide timestamps and event IDs, not a causal stream position.
The issuer must define ordering from acquired facts and keep a suppressed unrelated
candidate from clearing the current different card. The [publication plan](collaboration-notification-publication-plan.md)
tracks domain issuance/revalidation, serialized native ownership, Android/iOS actions,
retained-input review and notification-specific process-death acceptance. This journal
alone activates none of those surfaces. Chat push remains off pending usable native
publication and callbacks. These are HRIS contracts and are not ported to Starter.
