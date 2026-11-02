# Reading and reviewing retained notification replies

`ReadCollaborationNotificationReply` looks up an opaque input ID under the current
native account, checks ownership after storage, and checks the retained company
admission before returning any text. Expired credentials and missing conversation
history do not erase owned input. A replacement native family for the same account
can read its old input for review; this does not make the old action token current.
Returned data is a snapshot, not an enduring UI or notification publication lease.
UI owners still observe account/admission changes and discard obsolete projections.

`ReviewCollaborationNotificationReply` accepts the exact retained input version and
reproduces the facts that justify a hold:

- A valid immutable command for this account/company and operation has different
  text or conversation: `MessageChanged`.
- No retained command exists and the original local/native family changed:
  `SessionChanged`.
- No command exists in the original family, and the acquired direct conversation
  or exact original event is absent: `ContextUnavailable`.

A matching command needs completion, so review returns no new hold. An original
tombstone remains a valid event fact. Malformed/cross-owner returned commands or
malformed event facts fail without permanently reviewing input. Storage failures,
failed refreshes and company denials do not become permanent review reasons. Denial
still prevents exposing company input while preserving it in encrypted storage.

Review never sends, opens a token, changes typed text, clears a composer or removes
an input. Already-reviewed input stays reviewed. Holds use exact-value/revision CAS;
an older callback cannot overwrite a newly reviewed or re-created input. A successful
hold is read back and checked for the same candidate, creation order, generation and
requested review, with an advanced revision. Ownership and admission are checked
around pending reads/writes. Cancellation or ownership loss after a commit withholds
the result while keeping held input.

Both paths have a ten-second budget. The review owner rejects overlapping attempts
without accumulating work. A null review result grants neither sending nor completion;
the application must still use the retained input and admitted command flow. Existing
holds are not automatically cleared when context changes. Explicit review/resubmission
and bounded recovery remain required at the application/UI boundary.

This increment supplies use cases and their actual data-module bindings. It does not
activate a native receiver, automatic reply workflow, recovery cursor, review page or
notification display. Chat push remains disabled until the complete owned native flow
is implemented and validated.

Validation: [recorded checks and explicit scope](measurements/chat-notification-reply-review-2026-11-02.json).
