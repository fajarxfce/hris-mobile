# Command archival with replica publication

`CommandStore.archiveAndMutate` archives one exact active command and commits one or two prepared
replica mutations under the same account and session. An incompatible command, checkpoint or
generation returns false without writing. Storage constraints roll back the entire transaction.
The caller owns completion policy, encryption, admission and finite recovery.

An exact existing archive returns true without replaying replica mutations. Later progress and
ciphertext survive a lost-response retry. Every proposal is still validated and detached before
I/O, including replay; mismatched command bytes or evidence return false. There is no new schema,
timer, retry loop or event bus.

Room uses one public writer transaction for the existing command and replica DAOs. Explicit
invalidation refresh happens after leaving the outer transaction, including cancellation or an
uncertain response. IndexedDB uses one transaction across the existing account, command, archive
and replica stores. Database composition supplies both existing invalidation channels; a successful
archive notifies command and replica observers. Its ordinary replica operations retain their
previous protocol. The unchanged continuous-observation regression caught and verifies this
previously missed notification; no polling or second event bus was introduced.

Fifteen regression cases are declared for each database platform. Local JVM/browser execution covers mixed
mutations, command and replica conflicts, competing writers, exact replay, account renewal,
detached collections, cancellation, lost responses, continuous observation and reopening. A
forced failure while deleting the archived command proves that earlier replica writes also roll
back. An equal empty active inventory cannot erase the separately retained replica demand.

Focused 47, browser database 129 and full 15,939 pass locally. All 15,909 prior test identities
remain, plus 30 JVM/browser executions. Android compilation/lint, browser tests, Apple test-source compilation and the proof
APK pass. Actual execution of the 15 new Apple cases remains pending alongside 86 preceding cases;
combined control 43427620202 is running at exact Mobile 42fd5d12 and expects 19,362 cases.

The ten exact generic files are accepted in Starter 0d2b4ee0 after its independent 964-case gate. Group completion
and cancellation must supply a prepared encrypted repair Request to this transaction before a
fast archived original can be covered by the repair worker. Thirteen subsequent draft paths and
15 new shared tests prepare that source boundary; they are not applied or tested. Then integrate
one admitted demand stream with the existing command observer and the finite local worker.
No Group product graph, live service, phone build or native runtime is activated by this raw API.

Evidence: `docs/measurements/command-archive-replica-2026-11-13.json`.

The Starter port is pushed at `0d2b4ee0ad5dffd5552bfda37e399e50357e752a`. Its report digest is
`901d5ef80a1cc9fc12e6aa7141b08e99c85b73524198a98f852f2d1b2864f96b`. Actual Apple execution remains separate from its local gate.

## Actual Apple and portable verification

Control 43427620202 at 42fd5d12c5353779d4a61cc50b8c53ae8c6c0bc6 passes 19,362 cases, including 5,692 actual
Apple executions and all 101 new repair/archival cases. The host and 16 push fields pass.
Earlier pending Apple status is superseded for this exact source. Later terminal publication,
worker and product integration remain separate. Evidence: `docs/measurements/collaboration-inbox-repair-hosted-2026-11-13.json`.
