# Durable original references for inbox progress

Each new Group composer send now retains its exact immutable command intent as an encrypted repair
reference in the same transaction as source, empty editor, pending preview and recovery wake.
This reference survives composer materialization/retirement so a later owner can read the exact
active or archived command. Source ID alone and an absent queue row never establish delivery.

Reference completion and its ready preview/hidden-anchor publication use one index transaction.
The completed encrypted marker drops the original text and retains an encrypted digest of the
complete command intent, its identity and insertion revision. An identical handoff replay cannot
reactivate it; different content under the same ID cannot complete or replace it. Activity
anchors, actual message time, preceding candidates and independent typing stay intact.

A reference receives the raw index revision at first insertion. Finite encrypted cursors retain
that generation, captured high revision and last returned position. Completion or ordinary index
writes do not restart the pass. Later originals remain for the next pass. Generation/account
replacement ends the old pass. Each read uses one indexed subset with a limit of 1–32 plus one
lookahead, without history scanning or one stream per conversation. Empty owned reads create no
new key material. Physical Company, Group and history identities remain separate.

The domain proposal carries Retain(intent) or Complete(reference). The composer source codec
requires its exact command intent and publishes the reference under the same source/index CAS. The
inbox codec owns authenticated coordinates and encryption; its repair mapper validates retained
originals/markers. The read repository performs bounded raw I/O and mapping, without permission,
retry, scheduling or delivery policy. The future admitted command-progress owner remains separate.

Validation adds 13 real-storage methods on JVM and browser, including restart, lost committed
completion, concurrent index changes, cancellation before commit, hiding the latest message,
immutable proof mismatch, completed-marker text removal, bounded queries, generation removal,
renewed sessions, cross-account/Group cursor isolation and command-only recovery with no editor
envelope. Existing command-content, queue, composer, inbox and Company outbox cases remain. Full 15,763 retains all 15,737 preceding identities plus 26 new executions.
Android compilation/lint, Web, Apple source/test compilation and proof APK pass. Actual execution
of these 13 methods on Apple remains pending; control 43403331941 covers the preceding 81 methods
at 8a48212f and is tracked independently.

Exact command progress, a finite persisted recovery owner, incoming history producers and product
list/Group activation remain. The first candidate retained editor metadata absent from command
records; it was rejected after 114 focused passes and its partial full run was cancelled. The corrected reference retains the
actual command intent. Existing indexed originals still need a bounded source/command seeding
operation; a submit replay with an existing anchor does not perform that migration. Pending outgoing edits need their own producer/reference. No service or phone UI
is changed; the installed phone remains release 92.

Evidence: `docs/measurements/collaboration-space-inbox-progress-repair-2026-11-13.json`.
