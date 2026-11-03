# Focused conversation notifications

This increment is implemented in the working tree and is not yet accepted. Its
focused validation is running; see the current agent handoff for the result.

The thread state carries the actual account/company/native owner returned by local
context admission. Navigation owns a registration only while the route is resumed,
the window is focused and no departure, access failure or blocking draft/cancellation
dialog is active. Missing owned context cannot register. Lifecycle cancellation
releases the exact registration before replacement; closing an older registration
cannot clear a newer route. Focus is independent of visible-message read evidence.

The app owns one process-local focus holder per container. Registration validates
bounded owner identifiers and conversation identity, and a closed holder cannot
reopen. Each change invalidates the captured revision, including enter/leave races.
The publisher checks that revision around preparation and native I/O. The existing
bounded notification process observes changes and cancels/joins obsolete work.
Currently a route change also wakes other watched conversations conservatively;
measured behavior at the existing64-source bound remains a separate acceptance task.

For a matching focused conversation, admitted issuance retains the actual original
and carries its exact previous opaque native render. That render is removed before
conditional inactive settlement. A replaced native render is preserved. The journal
then becomes Cancelled with no message/action payload, and source admission,
original hash/timestamp, token ownership and the exact inactive write are checked
again. A confirmed inactive watermark prevents the original from alerting after
blur or process/container recreation. Missing history and uncertain work cannot
produce confirmed suppression or hint acknowledgment.

This reuses the encrypted publication schema. Issuance temporarily creates its
normal encrypted prepared payload/actions before inactive settlement; no native
show occurs on the matching-focus path. A focus change during an already pending
native show triggers exact compensation and withholds success. Lost committed
responses preserve the inactive watermark without claiming success. The OS boundary
is not an atomic transaction with route state or the database.

The implementation does not activate production native notification receivers or
iOS delegates. Chat push remains disabled. Device-encrypted pre-retention quarantine,
authenticated review UI, callbacks, permission wakes, historical/backfill baselines,
actual notification-input OS-death acceptance and integrated native delivery remain
open. Release20 on the phone predates this increment.
