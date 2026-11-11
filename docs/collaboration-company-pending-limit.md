# Company incoming page size

The Original incoming reader consumes at most16 pending references. Company traversal previously
omitted the attempted page size when advancing the seek, so the raw store validated that page against
its default20 and rejected a full16-row continuation. The repository now accepts limit:Int=20 and
forwards it unchanged to the raw store. Existing callers retain20. Domain owns the attempted size;
the repository only coordinates its datasource. Cursor/key bytes and storage namespaces are unchanged.

Four common storage cases exercise real encrypted persistence: a16-row pass through concurrent
resolution, process reopen and later appends; invalid limits and forged cursors; foreign Space frames
or physical devices; and changing page sizes within the same finite pass boundary. Resolving rows
cannot move pendingThrough or lose later references. An omitted default20 cannot acknowledge a
16-row continuation. Four domain fakes and the presentation history fake adopt the signature, with the legacy advance fixture
asserting that its caller still uses20.

A new Apple wrapper runs the existing eight wave cases and four new cases against Apple storage.
Linux focused 136 and browser 134 cases pass. Full validation accepts
11,895 cases with preserved prior case identities and unchanged dependency closures for reused
controls. Formatting, architecture, detekt, Android host/release compile/lint, browser/Wasm, iOS
metadata compilation and proof APK checks pass. Local Linux checks do not execute the Apple wrapper;
its12 cases require the separate hosted Apple audit. Portable hosted count increases by8 and Apple
by12 because the wave wrapper is new on Apple.

This change does not resolve message content, complete incoming tasks, retire wakes, enable a
collector, create a client or alter the live deployment. Original content retention, completion,
canonical LEGACY aliases, bounded native inventory, keys-only recovery and native acceptance remain.
All54 PRD groups remain open; no generic Starter change is needed.

Initial full1 failed presentation-test compilation after5,549 passing tests: the history fixture still
used the old repository signature. Adding its unused limit parameter fixes the compile error without
changing production behavior. The failed logs and original candidate are preserved; full acceptance
belongs only to the corrected source and subsequent successful checks.

[Exact validation evidence](measurements/collaboration-company-pending-limit-2026-11-11.json).
