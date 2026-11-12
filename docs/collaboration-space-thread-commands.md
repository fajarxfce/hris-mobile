# Canonical thread commands

`ObserveCollaborationSpaceThread` extends the existing canonical history observer with
durable local commands. Its snapshot keeps the typed history component and separate
Space and Original Company command lists. Equal logical IDs in different namespaces
remain separate records; reading an Original room never rewrites its ownership.

Both binding types observe canonical Space commands. Only an exact Original binding
also observes Company commands, using the binding's actual Company and conversation.
A Space-only room performs no Company message read. Pending, staging, staged, sent and
cancelled facts remain available; a sent state alone does not prove history retention.
The existing completion owner decides when a command can be retired.

Each source validates and captures its result before combining it with another
suspending source. Caller-owned lists and nested staging baselines cannot mutate a
previously captured result. Existing bounded inventories, edit validation and stable
creation-time/ID ordering are retained.

Unread sources have an explicit internal state. Successful publication requires real
results from every selected store. A known failure publishes immediately even when
another store has not emitted. A regression first demonstrated that ordinary `combine`
withheld this failure; the fixed composition preserves that exact assertion. It does
not manufacture an empty queue or add an automatic retry.

The original observer still owns session observation, Group admission and the sticky
denial state. It checks current ownership before publication and cancels all selected
readers on revocation, logout or account replacement. The final emission remains
inside that cancellable reader, with no extra buffered publication layer.

Tests retain the previous history, routing and admission identities. New cases exercise
all command states, duplicate/foreign records, immutable captures, independent updates,
known failures, slow consumers, cancellation and real encrypted storage reopen. The
reopen fixture renews the local login: assertions require the renewed access, reject
the old session, and preserve all history and command facts.

Focused validation passes 54 cases. Full validation accepts 14,724 cases: 12,686
executed now and 2,038 exact unchanged reports reused. All 14,688 prior case IDs
remain, with 36 new JVM/browser cases. Full check, source quality, Android release compile/lint,
shared Wasm/iOS ARM64 compilation and proof assembly pass.
[Exact evidence](measurements/collaboration-space-thread-commands-2026-11-12.json) retains
the two failed candidates, source/report digests and complete audit provenance.
Linux compilation executes no Apple tests.

This bound-room contract performs no API or SDK operation and changes no data format.
Unresolved room bindings and composer context precede organization chat presentation.
An absent binding is still unavailable, never proof of empty remote history. Group live
rollout remains disabled. This increment claims no new phone latency or native Apple
SDK behavior.
