# Durable push priority hints

Chat receipt retains at most 16 scheduling hints per account and native-session
family. Each hint contains the producer event ID, company/device IDs, revision,
issued/expiry times, and optional paired Matrix room/event IDs. It contains no
message body, sender name, display text, navigation command or token. All models
redact their string representation. Printable ASCII provider identifiers match the
native room API; revisions remain exact positive `Long` values on every platform.

A valid locally owned push first writes a fresh global recovery wake. A priority
attempt receives at most 750 ms and three compare-and-swap attempts, followed by
the existing fixed scheduler request with its 1.5-second bound. The entire receiver
retains its 3-second deadline. Priority storage failure, contention or its timeout
still allows the independent full sweep to be scheduled. Caller cancellation is
propagated and pending work is joined. A changed local account, audience, local
session or native family cannot publish success after an asynchronous operation.
Ordinary credential age does not remove local queue ownership.

Priority is optional acceleration, not the recovery ledger. Expired/future packets
still request the full sweep but are not inserted as new priority. Expiry cleanup
affects scheduling metadata only. Newer revisions replace a company/device hint
in its current slot; older revisions and equal-revision changes to event/room
identity are ignored. A producer retry of the same event may extend its timestamps.
New company/device pairs append, evicting the oldest hint only at capacity. The
full fair company/room sweep covers dropped and coalesced hints.

The queue uses the existing account-owned ReplicaStore and protected history key.
Its distinct partition and AEAD context bind audience, account, native family,
generation and format version. The local session ID remains a transaction ownership
check, allowing same-family renewal to read retained data. A different native
family has a separate queue and cannot transplant ciphertext. Payloads are capped
at 24,000 plaintext bytes and 32,768 encoded characters, without enlarging generic
database limits. Input lists are detached before suspension. Missing keys cannot
be regenerated over retained history or priority data, including inactive accounts.

Every replacement, including an empty queue, gets a fresh generation. Completion
accepts an exact captured hint and performs one snapshot CAS. A changed snapshot
returns false and retains newer work; completion never rebases an old acknowledgment
onto a new generation. Reads and completion recheck local ownership after I/O.
They neither acquire server admission nor start the SDK.

A receiver write and a scheduler request are separate operations. Cancellation can
leave a retained wake/hint awaiting the existing startup/periodic recovery path;
this is not proof of immediate OS execution after process death. The forthcoming
priority processor must acquire current native/company/device admission, resolve
the owned summary, independently compare its room binding, and own its SDK leases.
Notification privacy, read/mute policy, display and durable reply actions remain
separate work. The server chat producer remains disabled until that path is usable.

Tests exercise pure queue policy, receiver budgets and cancellation, actual SQLite
and IndexedDB persistence, competing repositories, stale completion, account/family
changes, ciphertext transplant rejection, lost keys, maximum escaped payloads and
lost commit replies. The real application-container test receives chat without a
workspace or SDK, reopens the container, receives a newer revision, and rejects
completion of the older snapshot. See [measurements](measurements/chat-push-priority-2026-11-02.json)
for exact checked sources, outcomes, corrections and hosted Apple scope.
