# Durable quoted-reply intent

Drafts and outgoing message intents now carry an optional original event reference.
The reference is encrypted in the existing owner-scoped draft/outbox records; quoted
body text is not copied into another durable snapshot. Legacy plain records retain
their encoding and remain readable. Outgoing reply IDs obey the gateway's 2–255
printable ASCII bound. Existing historical IDs are not globally reclassified.

Save replay, interrupted freeze, queue retry, acknowledgement and held-draft recovery
compare text and reply target together. A lost local response can recover the same
intent; the same logical send ID cannot silently move to another quote. A concurrent
newer draft is preserved, and confirmation clears both text and reference only after
the exact command is durable. Ordinary aged local authority still permits owned
editing/queueing; known denial and session replacement remain enforced.

Background draft recovery propagates the original reference without opening chat.
The native paused queue receives the exact `m.relates_to.m.in_reply_to.event_id` field
through its existing `sendWithExtraContent` path. The complete plaintext fingerprint
includes this relation, retaining original logical ID and transaction recovery.
No online target-event lookup is introduced. Preparation and enqueue validate the
same reference; the raw datasource only encodes and performs SDK I/O.

Validation on the final unchanged 4,052 source inputs: root check, Android product
compilation, browser data tests, format and architecture pass in 501.591 seconds;
3,401 fresh cases, zero failures/errors/skips. Coverage includes exact target identity,
invalid Unicode/surrogates, interrupted save/freeze/queue, concurrent target replacement,
encrypted SQLite/IndexedDB reopen and same-key retention, and application-container
worker recovery with its simulated SDK. The earlier compile failure and successful
pre-alignment gate are retained in [evidence](measurements/chat-quoted-draft-2026-11-05.json).

This commit is the durable pipeline, not product quoted-reply acceptance. Selection
UI, quote rendering, indexed local quote observation and history metadata integration
are the next work. Then prove actual SDK encrypted exchange and cold offline recovery,
deploy the validated gateway and deliver a new product APK. The current phone release32
and live backend8b40/schema116 are unchanged. Main gateway contract is
[d4f34140](https://github.com/fajarxfce/hris-platform/commit/d4f34140).
All54 communications acceptance groups remain open; no Starter port applies to this
HRIS feature.
