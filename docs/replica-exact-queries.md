# Coherent exact replica queries

`ReplicaQuery(id = ..., limit = 1)` reads an exact published document together with other selected pages under one checkpoint. The identity is independent of sort order. Tags and forward/reverse cursors cannot be combined with an exact ID; existing page and aggregate bounds still apply. Missing or deleted identities produce an empty page. Existing query constructors and serialized inputs preserve their defaults.

SQLite uses its existing composite primary-key DAO read inside the same query transaction. IndexedDB uses its composite object-store `get` inside the existing transaction. Both retain published generation visibility and account ownership checks; neither exposes unfinished staging. This adds no migration, network call, timer, observer or feature policy. The input query list is captured by the existing observation lifetime.

The immediate consumer is the recipient draft/source-marker frame: its two identities can be read without selecting every peer draft. This raw capability does not itself create a recipient conversation or pending-message projection. The repository must still authenticate both records, observe source changes, bound original decoding, maintain canonical association and preserve independent later typing. New recipient product routes remain inactive.

Four new cases run against each real local backend: exact plus indexed pages with different identity/sort keys, deletion and revival; coherent publication with staging/account replacement/disposal; malformed selectors and retained caller input; and database reopen with unfinished staging. Apple wrappers use the same contract and are source-compiled locally. Actual Apple runtime and phone interaction are separate.

The generic source has an independently validated Starter port recorded below. Phone release 93 remains installed; this change is not in that APK. Live Group remains OFF, V117–140 remain undeployed and all 54 communications PRD groups remain open. Preserve earlier failed Apple evidence and the blocked SQLite fixture preparation.

Validation: 18,177 cases (6,756 fresh on the exact source across focused/full gates; 11,421 exact unchanged), preserving all 18,169 prior identities plus 8 executions. Focused 179 and full root/Android/browser/Apple-source gates passed. Actual Apple and phone runtime remain separate.

[Exact validation](measurements/replica-exact-queries-2026-11-14.json).

## Independent Starter port

Starter `c7323ce89dd4da2eed61db6ab8deefd230296c63` independently accepts the exact eight generic paths with 1,060 cases (512 fresh / 548 exact unchanged), preserving all 1,052 prior IDs plus 8. Full session 63612 reaped 0. Android variants, production Web, SQLite/browser and Apple source compilation passed; no new device runtime claim.
