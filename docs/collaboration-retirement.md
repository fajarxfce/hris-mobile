# Message completion and pending capacity

The raw completion archive is implemented in `core/database` and passes validation
in Mobile and Starter. HRIS retirement policy and UI integration remain the next
increment. See [raw storage](command-storage.md) and the
[measurement](measurements/command-archive-2026-11-01.json) for the exact validated
scope; this document is the continuation contract, not product acceptance.

A Sent command must leave the bounded pending queue without making its logical ID
available for another send. `CommandStore.archive` therefore retains the entire
observed encrypted command and opaque completion proof while removing pending work
in one account-fenced transaction. It compares the complete record, not revision
alone: a delete/reinsert at the same revision cannot receive an old completion.
A retained archive also fences later insertion of that ID. Ordinary removal and
updates cannot mutate archived data.

## HRIS integration to implement

1. Add an encrypted completion codec under the existing command content key, with
   a separate authenticated purpose. Bind the proof to the complete retained raw
   command and owned audience/account/company. Preserve receipt aliases without
   duplicating the full message body in the bounded proof envelope. Validate all
   fields on archive reads and retain key-loss protection over inactive archives.
2. Make exact message lookup and insertion replay recover validated completion
   records. Pending observations continue to exclude archives. A concurrent archive
   must never turn a replay into a fresh queued message. Preserve immutable original
   intent, device/bootstrap/room, transaction and Sent event identity.
3. Admit local retirement only for an exact Sent mapping, durable receipt and acquired
   original history with the trusted sender/logical ID/full-content hash. All receipt
   aliases must be retained by that original. Legacy commands without this evidence
   remain unresolved. This operation requires owned local access and the company
   gate; it does not acquire an SDK client, fetch history or resume delivery.
4. Read retained completions when reconciling journal entries whose pending rows
   have already been archived. Validate them as exact confirmed facts, without
   recreating commands. Keep this recovery bounded by journal and command limits.
5. Remove only an exact observed receipt batch under the encrypted journal's current
   checkpoint CAS after its completion is durable. Archive and journal have separate
   transactions. Interrupted cleanup must be restartable without interpreting a
   retained receipt as unknown work. A changed journal or new aliases must be
   revalidated against completion identity and acquired history before removal.

Recheck owner and denial after every suspension and prevent late publication after
account replacement. Already committed completion facts survive failed responses
and late denial. Keep retries explicit; never evict uncertain commands to make room.
Validate cancellation, competing writes, archive/cleanup interruption, new aliases,
missing originals, key loss, reauthentication and same-ID retries on actual SQLite
and IndexedDB, then extend the dedicated native proof before UI scheduling.

Local message deletion, body retention, E2EE backup and native queue anomalies are
separate policies that must account for archived evidence. This storage capability
does not accept those features or any Chat/call/meeting PRD group.
