# Exact acquired-event classification

A received provider event keeps its own event ID, sender, server timestamp and type. The
common mapper separates decrypted originals, replacements, redaction target requests,
redacted targets, unresolved decryption and unsupported content. These facts do not admit
local retention or advance a traversal; the owning runtime and usecase remain responsible.

Originals and replacements use the existing content mappers and canonical hashes, including
unknown HRIS fields. An edit cannot establish the original message identity. Incomplete
replacements and unsupported types do not become original text or exhaustion. SDK decryption
is required before parsing message text independently of room membership/encryption flags.
NOT_DECRYPTED also covers malformed ciphertext; it cannot establish plaintext or key readiness.
Successful SDK decryption does not prove sender trust.

A standalone redaction only supplies a bounded target-read trigger. Metadata on the target
keeps that target's original sender and event identity, including when an edit is deleted.
The parser checks matching redaction target, actor/event/timestamp/optional room fields and
both legacy and modern target locations; conflicting targets are rejected. A redactor never
becomes the original sender. These parser tests do not establish actual native SDK redaction
semantics or authorize a storage change without the owning admission flow.

Requested identity, optional room identity, sender, type and integral server timestamp are
validated before producing a result. UTF8size/nesting limits apply before parsing/hashing.
Malformed metadata/content fails without publishing a partial original. Cancellation remains
cancellation, and diagnostic strings omit content.

The measurement records21 new common cases and7 existing original/7 existing replacement
mapper cases on Desktop,Androidhost and actualChromium, plus iOS source/quality checks.
The old mapper source and its case identities stay unchanged. Native parent integration,
actual SDK6 original/edit/redaction/missing-key proof and owning persistence remain next.
The existing acknowledgement reader also needs a native regression for an edit at provider
head: its presentation timeline may aggregate that edit. No product incoming route, phone
update, server deployment, SDK distribution change or Apple native Matrix implementation
is claimed by this increment.
