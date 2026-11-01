# Bounded local chat browsing

The thread reads one coherent window of at most 150 saved originals. Older and Newer move
that window with 50 overlapping originals, allowing Compose to retain a visible message key
while other rows leave memory. There is no ten-page stopping point or growing cursor back
stack. Eviction affects the projection, never the encrypted saved history. The 603-original
SQLite/IndexedDB regression crosses the former 500-message boundary and returns in both
directions, including reopen, a new arrival and an in-place redaction.

The repository accepts mutually exclusive after/before anchors, a limit of 1–150 and overlap
of 0–50 smaller than the limit. Defaults remain 50 without overlap. One indexed transaction
returns at most limit + 1 documents and its checkpoint; every selected document and the
lookahead are authenticated before publication. Results always contain newest-first messages.
The next/previous cursor excludes its anchor and includes the requested overlap. Reverse
reads seek the nearest smaller keys in descending order, then normalize the selected window.
An empty historical window or reverse arrival at the head reopens the live head.

Raw `ReplicaQuery.before` uses existing SQLite and IndexedDB indexes, with the existing
four-query/200-row total bound. It cannot be combined with after. Tagged and untagged reads,
publication, deletion, reauthentication and cancellation retain the same transaction/ownership
contract. No database migration or plaintext index is added. The portable eight-file change
is also validated in Compose Fluent Starter; HRIS window policy and UI remain here.

Only one local history observer remains active. Selection changes cancel the preceding read,
and publication checks its captured selection and account. Known denial clears content and
input; ordinary credential age does not hide owned history. Local loading retains the current
rows and composer, drops duplicate paging actions and allows return to the latest window.
Stable message keys retain the overlapping viewport at 320 dp with 200% text.

While reading an old window, pending commands and live events outside it are not mixed into
that window. Matching live edits/redactions still update a visible original. Newer appears
at the newer boundary, and a compact return-to-latest action stays above the composer. This
is navigation, not an unread count or read receipt. Sending from history returns to the head
only after local queue success and that head's publication. Draft text survives navigation
within the current ViewModel; durable draft/process recovery is subsequent work.

Provider paging retains its separate cursor and finite screen-owned operation. Local Older
reads take precedence; after local exhaustion an admitted connected timeline can acquire the
next provider page. Provider exhaustion still does not prove complete decryption. Incoming
push/replies, rich messages, durable drafts, cancellation, native Apple/web SDKs and all
unaccepted PRD groups remain in scope. The physical-iPhone waiver does not waive implementation
or simulator checks. This does not constitute large-company capacity or full chat acceptance.

[Validation](measurements/chat-history-window-2026-11-01.json) records source hashes and every
attempt. [Large-text screenshot](images/chat-thread/chat-thread-window-large-text.png).
The prior [native worker/foreground proof](collaboration-native-worker.md) has its own source
and acceptance boundary; local long-window tests do not substitute for that native proof.
