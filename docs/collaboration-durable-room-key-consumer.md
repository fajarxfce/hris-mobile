# Durable room-key callback retention

RetainCollaborationSpaceRoomKeys owns one serial application collector. A second collection waits
before registration or observation. The owner admits the current root, session, directory and
registered original source before explicitly starting the existing listener. It classifies known
rooms through local Original/Published indexes; unknown mapping or reconciliation requests a bounded
account pass. A failed lookup remains a failure and cannot acknowledge a callback.

One exact source/runtime/UUID receipt commits atomically with history intent and the incoming wake.
Schema 9 requires both receipt and history; versions 1–8 retain their prior meaning. Worker completion
and unrelated producers preserve the receipt, allowing lost-write confirmation after progress changes.
The raw occurrence UUID remains the request generation across retries; replay adds no request or wake.
A later same-room occurrence retains its own later pass. No key material or credentials enter the receipt.

Each batch has at most three five-second local attempts, with 250/500ms backoff. Storage/timeout and
explicit write contention may retry; authority errors and unavailable registration terminate. Failure
does not restart the observer. Exact raw ACK follows durable confirmation and current admission. A
negative ACK consumes nothing and cannot release a successor. Advisory scheduling does not undo the
retained wake or acknowledgement. Cancellation preserves any already committed intent.

DI enforces one collector instance, and tests exercise actual admission contracts with controlled SDK
and storage responses. Application cancel/join wiring, source/restore producer activation, native
integration proof, and the Apple native key adapter remain separate work. No production callback
collection is activated by these bindings alone. This stage changes no phone build or live deployment.

[Exact validation evidence](measurements/collaboration-durable-room-key-consumer-2026-11-12.json).
