# Incoming targets retain their original company

The Group incoming queue previously identified every logical task by a published conversation ID.
An unpublished company chat has a different identity: Company plus original conversation. Reusing
its bare ID would let unrelated companies collide or send original work into Space history. The
queue now carries Published(conversationId) or Original(Company, conversation) explicitly.

Uniqueness, source selection, physical conversion, FIFO deferral, provider phase transitions,
completion and lost-response confirmation compare the complete target. Original tasks require a
concrete installed source and valid nonzero company/conversation coordinates. A published request
cannot replace an Original with the same UUID. A different company cannot confirm another original's
write or consume its generation. The existing source cursor, inventory loss, retry, wake and combined
logical/physical capacity remain unchanged. There is no unqualified conversationId getter.

Encrypted v4 parts carry discriminated room targets. Existing v1/v2/v3 remain readable, and queues
containing only Published targets keep the previous encoding. Removing the last Original can return
to v3 without losing source positions or a pending wake. The existing 64 combined room slots, 64
source slots, 129-document snapshot, 130-document sentinel and 4MiB aggregate limit remain. Markers,
schema, target variants, Company, source and the full queue validate before exposure. Unknown or
ambiguous variants are rejected; old IDs are never guessed to be company originals.

Published Load, Advance, Recover and Complete reject Original before repository or SDK I/O. Generic
deferral and confirmation retain full targets. This increment does not produce Original tasks or
enable the raw collector. Original metadata, actual Company provider/content/completion consumers,
bounded native inventory, keys-only recovery and native proof remain necessary before activation.
No selected HR company or stale native parent becomes a durable target owner.

Validation accepts 620 desktop and 553 browser cases, then 11,557 full
cases with no failures, errors or skips. Twenty-one new domain cases and eight common storage cases
cover namespace collisions, source/generation matching, lost write replies, owner guards, exact old
encoding, encrypted reopen across sessions/selected companies, capacity, malformed authenticated
payloads and interrupted commits. Every previous case identity remains. The first focused run's
single failure was a new test reading with its immutable old fixture session after activating a
replacement; only that invocation was corrected, with the failure and original candidate retained.
Full validation includes format, architecture/detekt, Android host/release compile/lint, browser/Wasm,
iOS metadata and proof APK assembly/unit checks. Sources and library fingerprints stay exact.

No new native SDK execution, Apple runtime acceptance, phone installation or live deployment is
claimed by this local increment. No generic Starter code changes are required. All54 PRD groups
remain open; this is a completed prerequisite for the continuing incoming-sync work.

[Exact validation evidence](measurements/collaboration-space-original-incoming-target-2026-11-11.json).
