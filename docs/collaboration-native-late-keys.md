# Native late-key retention

A real SDK8 key now has an accepted Android scenario while another receipt's local
transaction is already committed and its reply is held. The fictional peer rotates
its outbound session and sends a fifth encrypted event before adoption; the source
receives that new key through one actual sync during the held reply. Releasing the
first reply as a storage failure preserves the committed witness without rewriting it.
The second receipt commits its own recovery obligation before acknowledgement.

The evidence requires two different receipt generations, two commits and two native
acknowledgements, the same runtime instance, an empty raw receipt stream and an
unfinished first recovery generation with the second generation retained as requested.
No product retry count, deadline, schema, SDK artifact or delivery policy changed.

After receipt processing, native API and non-sync Matrix counters stayed unchanged
for 30009 ms. The application source collector remained alive during that
window. The existing runtime-owned sync pump continued its 10-second long polls with
persisted cursors. The fixture bounds their count, requires actual completed waits of
9-20 seconds and start spacing of at least9 seconds, and rejects additional key/history
or native API requests. This measures normal realtime traffic rather than assuming a
connected application makes zero HTTP requests. A separate process, with the owned server transport mapping removed, read the
same retained receipt and recovery generations without opening the SDK, then released
the database. This proves durable work survives reopening; it does not claim the new
message was already decrypted or that the pending history worker ran during the quiet
window. The fixture replaces interactive activation and OS scheduling, while actual
application ownership, repositories, encrypted storage and SDK callbacks are used.

All eight native cases pass, including the seven previous event/history/original and
registered-worker controls, in 368.326 seconds on a fresh 2560 MiB,
two-core emulator. No compiler overlapped it; the emulator stopped and the phone was
untouched. The late-key case recorded 29 Matrix and
7 native API requests in total, including staging. Full local
Mobile validation accepts 14,581 cases (952
fresh, 21 matching current proof inputs, and
13,608 unchanged dependency-verified cases). The proof build
passes 21 tests and the Python protocol gate passes 58, including 10 new validation cases.

The first native attempt stopped before the receipt experiment because the new fixture
compared send order with the provider's event-ID sort. Its three actual event identities
were correct and unique. Sorting the expected identity lists fixes that test contract;
the failed input snapshot and isolated userdata remain retained. The second attempt
proved the receipt invariants but rejected three expected 10-second sync polls during
30 seconds because the initial fixture required zero total requests. Source inspection
confirmed runtime-owned long polling. Its replacement requires measured bounded polling
and unchanged non-sync counters; no product timer or receipt/recovery assertion changed. Evidence binds the exact source, APK, native library,
reports and original case identities.

The result is an SDK8 proof, not a new phone build. Release89 still uses SDK6; live Group
remains disabled. All54 PRD acceptance groups remain open. Native Apple SDK8 execution,
account replacement and application-level incoming delivery remain separate acceptance.

[Validation evidence](measurements/collaboration-native-late-keys-2026-11-12.json).
