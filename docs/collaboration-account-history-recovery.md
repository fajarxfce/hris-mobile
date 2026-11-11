# Account/root history recovery state

Incoming demand now retains one optional history recovery record in its existing account/root
header. It can exist before any provider sync position. An active generation owns the metadata
cursor, an incrementing turn revision, unresolved evidence and an optional operational delay;
a separate requested generation coalesces newer demand without rewinding active work.

Each admitted page or deferral advances the revision, so an old capture cannot match after a
later metadata round returns to the same cursor. Page advancement preserves a newer request and
rejects disappearance of an already captured request. If a visited history has another bounded
reference page, metadata EOF starts another round of the same generation. Unresolved content
alone completes the pass and waits for a new relevant trigger. Once finite history passes finish,
EOF promotes the latest request. Replays of the current completed generation remain idle.

These are pure scheduling rules. The future operation owner must verify page coverage, native
content authority and outcome meaning; operational failures and content awaiting keys are distinct.
The state does not supply an event-producer receipt or deduplicate arbitrarily old callbacks.

The existing encrypted incoming-demand codec uses explicit schema7 only when this record exists.
Schemas1..6 remain readable and retain their no-record encoding. Old multipart headers reject a
recovery record, schema7 requires one, and old monolithic decoding remains strict. Canonical
counter encoding,160-character ASCII cursor bounds and complete-state invariants are checked.
The header retains the same AAD and limits:65,536 plaintext bytes per part,131,072 encoded chars,
129 parts with a130 read sentinel and4MiB total. No room queue or source position is fabricated.

Primary/source mutations preserve the record. Wake completion keeps an active recovery pass but
allows completed unresolved work to sleep; a later producer must retain a fresh request and wake
atomically. Existing final admission and wake compare-and-set protect completion races.

Validation adds24 pure policy cases,4 wake cases (19 total), and12 actual database cases (65 total).
They cover coalescing, cursor and revision replay, round fairness, completion versus waiting,
capacity129, authenticated malformed headers, older schema rejection, Original and inventory
preservation, competing snapshots, response loss, cancellation, root scope and reauthentication.
13,148 local cases were accepted with previous case identities and unchanged dependency
closures verified. Exact evidence is linked below.

This state is not an activated consumer. Do not attach a key producer until the worker can serve
bounded recovery turns; retaining a wake alone could otherwise keep idle work pending. Historical
content owners, reference/global readback, primary EOF demotion plus wake, producer acknowledgement,
key-listener lifecycle and loss reconciliation remain. Snapshot stability of a whole catalog under
continuous insertion and full blocked-queue liveness are not claimed. No producer, collector,
Starter or live setting changed. All54 communications PRD groups remain open.

[Exact validation evidence](measurements/collaboration-account-history-recovery-2026-11-11.json).
