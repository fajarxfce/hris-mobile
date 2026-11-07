# Durable original room non-execution evidence

An explicit server fence now has a distinct Room-only `Fenced` command outcome.
It retains the exact original request and the server fence identity/time. A setup
receipt remains `Recorded`; neither outcome means that a message was sent, delivered
or read. [Typed server resolution](collaboration-space-room-request-fence.md) and
[original command storage](collaboration-space-conversation-commands.md) remain separate.

## State and storage

Only an already Unconfirmed Room command can become Fenced, with the same attempt
count and a new exact storage revision. Evidence must match the complete immutable
original, use a nonzero UUID and a supported microsecond server time. Both terminal
outcomes reject further transitions. Open commands cannot contain fences.

The encrypted request, original native binding, account partition, key and v1 AAD
are unchanged. Existing Pending, Unconfirmed and Recorded outcome envelopes continue
to use schema1 and omit the fence property. Only new Fenced outcomes use schema2,
including the account and all seven stable original server fields inside authenticated
ciphertext. Decoding verifies those fields against the immutable request, then applies
the domain record policy. Mixed schema/phase/receipt/fence/failure state is rejected.
This preserves old stored records for the new reader; it does not promise that older
applications can read the new outcome.

The repository's existing exact compare-and-set transaction persists the new outcome.
A lost response is reconciled from the original ID and authenticated record; it never
re-encrypts the immutable request, generates replacement keys or increments a send
attempt. Competing terminal writes have one winner. The canonical conversation-time
check stays at acquisition; storage validates the complete original and structural
evidence without treating a device clock as server truth.

Fenced remains active and occupies its original resource. `archiveRecorded` deliberately
rejects it. Atomic retention of original evidence and a successor link must precede
replacement; storing a fence alone does not grant that handoff. Current admission,
bounded owning capture and room projection remain separate from the repository.

## Validation

[Exact sources and results](measurements/collaboration-space-room-fence-storage-2026-11-07.json):
focused99362 passed65 cases in217.124s. Full22532 passed
3,697 fresh cases/455 suites in513.19s,
with4,398 unchanged inputs, zero failures/errors/skips and release lint
issues. Root check, Android host regression, format/architecture/Detekt,32 selected
Browser data cases and shared Wasm compilation passed. The initial Browser domain
filter selected zero policy cases; that gap was caught during report audit. Supplemental
browser-policy82617 passed all6 actual Browser policy cases in26.512s
on identical source. Desktop and actual Browser each therefore ran21 command-storage,
3 Open-storage,8 fence-transport and6 command-policy cases. All original reports are retained.
All local jobs are reaped; native/Apple source CI remains separate.

Six new real-storage cases cover database restart under a new local login/company,
unchanged keys/request ciphertext, lost actual commit response, competing receipt/fence
writes, legacy schema1 archives, authenticated conflicting descriptors, unsupported
schemas and missing keys/foreign owners. Two new pure-policy cases cover Room-only
terminal transitions and exact original validation. The existing Open replay storage
regression still exercises normalization of durable error text before exact CAS.

No product caller, successor, SDK operation, new APK or phone-latency acceptance is
included. Apple storage wrappers inherit the cases and still require source CI.

[Owning finite resolution](collaboration-space-room-request-resolution.md) now uses this
storage with exact normalized marks and bounded capture. [Atomic successor storage](collaboration-space-room-successor.md) is also accepted separately; current-admitted preparation remains to be connected.
