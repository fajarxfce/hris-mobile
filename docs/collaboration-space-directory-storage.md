# Encrypted organization directory

The native organization directory has its own local owner and encrypted replica.
It does not reuse selected HR company, company chat permissions, or the physical
Matrix SDK store. The new storage adapter is a prerequisite for the product group
runtime; current product routes do not call it yet.

## Ownership and storage

`CollaborationSpaceLocalAccess` binds a current local request session to server
audience, account and organization space. The database checks that local session
and account in every transaction. Encryption binds audience, account, space,
generation, record ID, revision and sort key. Local session and native credential
lifetimes are deliberately excluded from the encrypted namespace, so a later login
for the same account can recover its owned facts. Another account/space cannot
read them. A caller must still apply the separate admission gate before publication.

Android, Apple, desktop and browser application hosts supply a separate
`collaboration-space-directory-content-key` through their existing protected
credential adapters. The isolated app container owns its lifetime. Missing keys
never trigger replacement while either directory or admission records remain,
including records belonging to inactive accounts. Empty reads create no key.
Opaque UUIDs are the only contact indexes; names and public company labels are
encrypted. These labels grant no HR record or conversation access.

## Bounded acquisition progress

The repository accepts one unfiltered native page of at most 50 contacts, each
with at most eight company labels. The full local projection is capped at 10,000
contacts. Each append authenticates the page and atomically commits documents and
its next cursor under exact generation/revision compare-and-set. A repeated or
late result cannot append twice. The terminal cursor is durable.

The first acquisition can publish its available pages. A later refresh keeps the
previous complete generation visible until the replacement reaches its terminal
page; publication then removes contacts absent from the replacement. Interrupted
refreshes resume the existing generation. Concurrent server changes across native
pages mean this is an eventually refreshed directory, not a server point-in-time
snapshot. Opening a chat still requires current server admission.

Local reads decrypt at most 200 records per database page and stop at the
checkpoint's authenticated count, without an extra end-page read. Every page must
share the same checkpoint. A generation change during decoding discards the mixed
attempt; the next storage emission can publish a coherent snapshot. Decoding is
cancellable and runs away from the main dispatcher. Pure name filtering/sorting
starts no network request. No age timer hides the retained facts.

## Admission retention

Known authorization, MFA and minimum-client denials use a separate encrypted
revision. A process-owned buffer publishes the denial before persistence and
retains it through failed or rejected writes. Ordinary provider unavailability
is not a denial. Successful reacquisition may clear only the captured admission
revision; the pending buffer is also cleared with one atomic compare-and-set, so
a newer denial wins even at the final clear boundary. Stale local sessions
cannot clear a current account's persisted gate.

The buffer has a finite 128-owner bound and fails closed without evicting another
owner. If all persistent writes fail, retention is process-local; successful
persisted gates survive process recreation and same-account reauthentication.
The repository exposes raw owned facts and admission separately. Owning use cases
must apply admission, current account capture, finite native acquisition and
scheduling; this storage change does not itself reopen a denied projection.

## Validation and remaining work

Final root `:check`, Android-host tests and Android dev host compilation pass in
346.21 seconds (1,370 fresh test cases; unaffected tasks reused).
The browser data suite passes in 96.303 seconds (486 cases).
All final inputs are identical across both gates. Seventeen shared directory cases
run on real SQLite and IndexedDB with AES-GCM; application tests resolve the real
new repository through the isolated container. Format, architecture and detekt pass.

Earlier attempts are recorded, including two new-test compilation corrections,
the initial 15-case SQLite pass, and initial full/browser passes before review found
a non-atomic final buffer-clear window. The final datasource uses a single CAS;
a boundary-injection regression covers both final clear paths. No gate result is
used to claim platform or product behavior beyond its actual scope.
See [measurement](measurements/collaboration-space-directory-storage-2026-11-04.json).

Remaining: owning local-read and refresh use cases, durable native identity/device
commands, SDK store adoption and hard-parent recovery, organization UI, and typed
group push/background/reply integration. This change does not establish product,
FCM, Apple runtime, Matrix trust or performance acceptance.
