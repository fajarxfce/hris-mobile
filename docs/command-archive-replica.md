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
the prepared combined control expects 19,362 cases and has not yet been dispatched.

Next, port these ten exact generic files to Starter and validate independently. Group completion
and cancellation must supply a prepared encrypted repair Request to this transaction before a
fast archived original can be covered by the repair worker. Thirteen subsequent draft paths and
15 new shared tests prepare that source boundary; they are not applied or tested. Then integrate
one admitted demand stream with the existing command observer and the finite local worker.
No Group product graph, live service, phone build or native runtime is activated by this raw API.

Evidence: `docs/measurements/command-archive-replica-2026-11-13.json`.
