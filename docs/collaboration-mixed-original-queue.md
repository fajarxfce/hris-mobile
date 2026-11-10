# Matching a mixed original message queue

Status: focused and full local application validation accepted. No
worker, screen or native sending capability is activated by this increment.

An adopted Matrix queue may contain original company messages and messages authored
for an organization conversation. The matcher retains those as distinct typed
Company and Space records. It never treats a space ID as a company ID or replaces
an original device, sender or transaction with the logical runtime's identity.

The complete captured queue must match exactly one authenticated local ledger for
each logical message ID. A missing or ambiguous match rejects the whole capture.
Queued, Cancelled and unresolved Staging records cannot become inferred native
originals. Staged/Sent records retain the exact physical device, transaction and
content origin. Every Sent record needs matching immutable completion evidence;
event aliases cannot belong to two messages across either ledger. Captures contain
at most 128 originals and completion proofs contain at most 512 aliases overall.

`ReadCollaborationMixedOriginalQueue` owns the bounded inspection. It checks the
local native parent and directory admission before and after repository I/O, reads
both ledgers for every captured ID, and detaches callback-owned lists before another
suspension. A failure reading either ledger is preserved rather than interpreted as
absence. Final binding, native inventory and runtime checks reject changes during
the read. Cancellation propagates; the operation has one 15-second deadline and
performs no automatic retry, remote discovery, credential renewal or message mutation.
Credential age and selected-company state do not hide owned local facts.

A successful result is a local snapshot, not permission to resume, settle or enqueue.
The consuming delivery flow must reacquire its owned native lease, verify the entire
paused inventory and enforce current transport/recipient admission before changing
anything. An empty SDK capture contributes no acknowledgement and does not prove
that the database has no pending commands. Recovery still must inventory commands
whose conversation has no local binding.

The domain contains matching policy and repository orchestration. Existing repositories
perform I/O, the collaboration data module binds constructors, and app DI tests resolve
the reader. The original-only native lease keeps its existing interface. No ViewModel
or UI helper owns queue reconciliation.

Focused validation passes 70 fresh cases: 17 matching and 17 reader cases on both
Desktop and actual Chromium, plus two application DI cases. Apple source and quality
checks pass. Coverage includes mixed/empty/single-ledger captures, exact upper bounds,
ambiguous ownership, completion aliases, credential age, permission/parent changes,
mutable callback lists, final inventory changes, cancellation and timeout. Full
application validation passes 9,556 verified results (7,537 fresh and 2,019 exact
accepted reused), with zero failures, errors or skips. Source and library inputs stayed
fixed. [Measured evidence](measurements/collaboration-mixed-original-queue-2026-11-10.json).
Hosted Apple and native/product acceptance remain separate.
