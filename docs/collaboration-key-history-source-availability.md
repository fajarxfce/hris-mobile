# Classify a historical owner outside the current source

An admitted Matrix source may belong to a different Company from a retained
history locator. The Original loader now returns typed SourceUnavailable after
checking that its registration and account/source admission remain unchanged.
It reads no Company metadata for that locator and makes no transport/key request.
Missing or malformed registration, source replacement, denial, cancellation,
deadline and storage failures retain their existing failure behavior.

The Space loader accepts an account-owned Space locator without dropping its
spaceId. A locator from another Space cannot look up the same conversation ID
inside the current root. After current admission and a final registration check,
it returns SourceUnavailable without a binding read. Current-Space callers keep
their existing API and behavior, including Original room aliases. Malformed Space
IDs fail before I/O; ordinary preparation expiry does not hide owned coordinates.

SourceUnavailable is a finite visit outcome, not proof that a history is empty or
complete. MetadataUnavailable remains separate. Future global recovery must carry
unresolved evidence, continue other histories and request a new pass when the
relevant source becomes available. No generic Conflict/Unavailable failure is
converted to a waiting outcome, and no retry or application worker is enabled.

The six-file change adds fourteen common cases (six Company, eight Space) and
preserves all fifty previous loader test identities. Two existing Company mismatch
tests intentionally refine their expected result from generic conflict to the
typed outcome and require the final registration read. Tests cover late ownership,
native session and source changes, denial precedence, missing registration, local
failure, cancellation, deadlines, malformed Space IDs and the account-locator
overload. Existing reference readers, metadata/binding storage and both DI orders
remain selected controls.

Planned focused validation is 158 JVM plus 156 browser cases. Full acceptance must
reach 13,626 local cases on the same 5,067 inputs, preserving earlier identities
and verifying unchanged dependency closures. This is HRIS domain policy; no
Starter port, listener, global acknowledgement, key probe or live deployment.

[Exact validation evidence](measurements/collaboration-key-history-source-availability-2026-11-12.json).
