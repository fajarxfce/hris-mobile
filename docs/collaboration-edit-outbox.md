# Durable edit commands

An edit uses a new immutable local operation ID and the reviewed original target.
The encrypted command retains the room, original sender/event/logical identity,
original hash, acceptance times and reply/forward context. Queueing checks owned
local access and the current original after each suspension. It does not acquire
credentials, open the SDK, send HTTP or alter the ordinary message draft. A lost
insertion response retries the same ID and exact content, including after the
authoring window expires. Consecutive local edits keep their creation order across
a backward clock adjustment.

The existing paused native send queue stages edits. The outer Matrix content
contains `m.replace` and a separate edit operation ID; `m.new_content` contains the
complete new text and immutable original context. A 256 UTF-16-unit legacy preview
avoids duplicating the maximum-size body and preserves surrogate pairs. The SDK's
typed body uses that exact preview because its typed fields override extra JSON.
Canonical fingerprinting and native construction use the same content. The full
new text is never truncated. SDK content and returned send handles are released
on success; cancellation releases the content without inventing a completed send.

Original text/forward matching, cancellation, command updates, receipts and
completion codecs validate edit metadata explicitly. An edit cannot recover an
original send using the same operation ID, or vice versa. Missing edit metadata
retains compatibility with existing ordinary commands.

Recovery uses separate bounded replacement evidence, including provider-accepted
edits that cannot be displayed. Exact operation, target, sender and complete
content fingerprints must agree; original receipt identity remains separate.
Accepted revisions retire through the existing immutable completion journal.
An ineligible edit for a visible original retains its command/receipt until
acknowledgement, allowing a durable failed-edit indication. Hidden or redacted
originals retire without inaccessible retained commands. Acknowledgement UI must
still bind captured access and the exact reviewed failed operation IDs before it
is exposed to users.

[Validation](measurements/collaboration-edit-outbox-2026-11-06.json): the final
full gate passed 5,065 fresh cases in 719.112 seconds,
with no failures/errors/skips and Android lint0. Source inputs stayed unchanged.
Root, browser, Android host, proof compilation, debug build, format, architecture
and static analysis passed. Earlier compile/static/fixture failures and stopped
attempts remain in the measurement. Real SQLite/IndexedDB cases cover encrypted
restart and immutable command/receipt storage; domain tests cover cancellation,
late ownership/denial, lost insertion responses, ordering and receipt recovery.
The Android host fixture checks actual typed-content arguments and handle disposal;
it does not load the native library or prove network exchange.

This increment is not wired into the composer. Stable original-bubble projection,
failed-edit actions, long-press editor, visual checks, actual native encrypted
exchange and complete raw cold-revision acquisition remain required before an
authoring release. The serialized-size regression is not proof of the final
native encrypted envelope size. Phone release 40 remains installed.
