# Bounded raw Matrix event I/O

The presentation timeline can aggregate a replacement into its original message. Its displayed
items therefore cannot serve as the complete event stream for incoming history. The new internal
raw-event datasource performs one exact SDK event read without a timeline or subscription.
It is an acquisition primitive; runtime binding and incoming projection follow separately.

The supplied Matrix client remains owned by its existing runtime. Each invocation owns its
room handle and closes it on success, technical failure or cancellation. Missing local rooms
fail without joining. The source starts no sync, send, enrollment, automatic retry or key
creation. Concurrent calls keep independent room handles and detached results.

The snapshot retains the requested room/event coordinates, current room membership/encryption
facts, original JSON and the SDK's distinct NOT_DECRYPTED/DECRYPTED/UNABLE_TO_DECRYPT result.
Room encryption does not establish successful decryption; decryption does not establish
sender trust. The mapper must bind the returned event identity and classify its content before
an owning usecase may retain it. Raw text is bounded to96,000UTF8bytes and malformed UTF16 is
rejected. The mutable SDK result is cleared after capture and DTO diagnostics redact content.

The next repository integration must register finite acquisition under the current native
parent, preserve physical and logical ownership across I/O, and reject late results after
revocation. Incoming progress must separately retain original and replacement evidence.
Raw redaction actors must never replace original sender identities.

Validation is recorded in the accompanying measurement. Fifteen new Android host cases cover
all SDK encryption statuses, exact unknown fields, detached results, missing rooms, cancellation
before/during/after acquisition, technical and cleanup failures, UTF8limits, membership facts
and overlapping reads. Eleven previous history-source cases remain unchanged. These are host
adapter tests against the actual SDK6 ABI; they do not claim native crypto execution, new
product activation, phone acceptance or Apple native Matrix implementation.
