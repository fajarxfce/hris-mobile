# Account-owned incoming history discovery

The incoming-history locator repository can now read published metadata for an entire owned
account. Each result retains its actual Company or Space owner, conversation and historical
device. This lets later recovery discover records beyond the selected company or current device
without inventing a source position or duplicating the history catalog. Existing Company/Space
scoped reads keep their previous behavior.

Each read consumes at most16 indexed metadata rows with one lookahead. The mapper validates the
actual160-character ASCII partition bound, publication, session/account ownership, generation,
revision, prefix and strict exclusive ordering, including the lookahead. UUID Company history
and explicit space/v1 history produce typed locators. Browse roots and reserved internal
projection namespaces advance the metadata cursor without becoming incoming work. A page may
therefore contain no locators and still have a continuation. Malformed recognized history and
unknown Space history versions fail instead of silently pretending the pass reached its end.

The repository maps acquisition and decoding failures inside safeStorageCall and propagates
cancellation. It reads no content, creates no encryption key, requests no provider data and
starts no observer beyond the first published result. Existing generated dependency injection
continues to own the repository. Account replacement hides foreign coordinates; reauthentication
of the same owned account retains its metadata. Removed cursor rows do not rewind a pass, and
new rows behind its cursor are found by a later pass.

Fourteen new common mapper cases and twelve added database cases cover namespaces,80 historical
device locations across companies/spaces, internal-only pages, staging, restart, account/audience
isolation, reauthentication, cursor deletion, lookahead, cancellation and acquisition failure.
The first focused run stopped after50 passing domain cases because the repository lacked a
ReplicaScope import. Adding that import fixed compilation; no test or behavior was changed to
address the failure. The failed candidate and its completed case identities remain recorded.
13,068 local cases were accepted with prior identities and unchanged dependency closures
verified. Exact evidence is linked below.

Metadata ownership is separate from live native authority. Historical Space roots and Original
Company sources still need their admitted content owners. This read API does not provide a
snapshot-stable catalog under continuous insertion or implement a global recovery worker.
Primary/recovery fairness, trigger-specific retry, key listener ownership, full blocked-queue
liveness and native SDK8 Apple proof remain open. No collector, producer, Starter or live
configuration is changed. All54 communications PRD groups remain open.

[Exact validation evidence](measurements/collaboration-account-history-locators-2026-11-11.json).
