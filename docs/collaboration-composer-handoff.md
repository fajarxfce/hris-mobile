# Composer handoff

Build 60 still disables the input while saving, freezing a draft, queuing its message,
and clearing the draft. Android consequently dismisses the keyboard. Simply changing
the field to read-only also ends its input connection; accepting new text into the same
frozen draft would risk replacing an original whose commit response was lost.

The next increment separates the immutable send from the editing slot. A local encrypted
transaction publishes both the submitted original and the next empty composer revision.
Subsequent edits own that new revision. Delivery recovers submitted originals by an index,
without keeping the screen alive, waiting for an HTTP response, or reusing the next text.
The eventual message must retain the original submittedAt ordering timestamp; recovery
order and the time a worker visits an entry must not reorder consecutive sends.
The existing ReplicaStore batch transaction can commit both documents in the same owned
company partition. No database schema change or cross-datasource transaction is needed.

Domain owns submission intent, admission and recovery policy. The repository maps the
encrypted outbox and editing documents through raw storage contracts. A use case will
orchestrate admission, atomic handoff and the scheduling hint. The ViewModel will capture
an input gesture, retain its identity while uncertain, and render results; it must not
implement the prepare/queue/confirm business sequence. The page only renders and forwards
input. Transient pending presentation never implies a durable commit or server receipt.

The new outbox uses a distinct authenticated document format and indexed tag. Existing
composer documents and legacy frozen submissions remain readable. Retiring an outbox
entry requires matching the exact durable message first; a missing entry alone proves
nothing. Each page reads at most eight originals plus lookahead; at most 128 originals
may await handoff to the message queue. Newer text survives retries, late completion,
another editor and lifecycle changes. Old account callbacks cannot publish into a new
account's editor.

Acceptance requires competing CAS, committed-response loss, reopen/reauthentication,
original command reconciliation, queue capacity, malformed ciphertext, bounded discovery,
cancellation and late completion tests before UI adoption. Actual Android acceptance
must demonstrate keyboard continuity, immediate pending placement, several consecutive
sends, and retained drafts/history after an offline cold start. This document and the
initial contracts do not claim those changes are already active in build 60.

The UI/UX Pro Max database had no verified Compose keyboard-session pattern for this
case; its broader focus-ring results were not applied as an IME fix. The Compose testing
skill supplies the plain-state focus/input seam, while Android runtime evidence must
prove the real keyboard connection. Its optional focus-navigation reference is absent
from the installed skill package.


Integration must keep the same submitted ID and submittedAt timestamp when converting
an outbox original into a durable message. Local projections should deduplicate the
outbox and command by that ID, preferring the actual command when both exist. Recovery
must run independently of the screen and preserve offline queueing. Retiring the outbox
requires a matching active or authenticated archived command, including cancellation;
absence or a successful HTTP request alone is insufficient. The current legacy frozen
workflow remains available during adoption. New typing, cancellation, and the chat-list
preview all need regression coverage before replacing the old composer path.

Storage foundation acceptance is recorded in [the measurement](measurements/collaboration-composer-outbox-storage-2026-11-08.json): twelve actual SQLite cases and twelve Chrome/IndexedDB cases pass. Root/native compilation and quality checks pass after the audited local inspector workspace lock update. The first class-only browser filter selected no cases; the corrected wildcard run has twelve concrete XML cases. This remains unused storage infrastructure until the owning use cases, recovery, projections and input gesture are adopted and tested.
