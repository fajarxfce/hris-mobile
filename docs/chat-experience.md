# Primary chat navigation and compact conversations

Chat is a primary Home tab beside Home, Inbox, Employees and Account. It is removed
from the service catalog. The tab renders the existing feature-owned inbox with
no redundant Back button; room/new-chat/saved-reply routes remain feature-owned.
Notification links establish the Chat tab as the parent before opening the room.
Home still owns tab selection and its Back-to-Home behavior.

Owned list/history/draft observation stays independent of provider acquisition.
A saved empty inbox shows its empty state during refresh; an unpublished empty
inbox still shows initial acquisition progress. Transport history synchronization
does not continuously animate the toolbar refresh icon. The composer remains
usable while older history is acquired. This is not a claim that all transport or
offline recovery work is complete.

Bubbles wrap their content with a bounded width and opposite alignment. Metadata
shows HH:mm, an optional edited label, and accessible clock/check/error icons for
queued/server-accepted/failed transport. Day separators show Today/Yesterday,
weekday names for2–6 calendar days ago, then dd/MM/yyyy. Both time and grouping
use the device timezone. A multiline pill field replaces the underlined desktop
input treatment; the send target remains48dp. No inert attachment/sticker buttons
are presented before their corresponding behavior is implemented.

When a room's provider traversal is established, its locally retained pending
references and exhausted checkpoint are read before publishing older availability.
An exhausted traversal with no pending references does not offer Older messages
again after ViewModel recreation. A committed provider end is checked before any
further provider request. Unresolved originals remain separately recoverable;
exhaustion never claims successful decryption. Account/access replacement still
clears the owned projection, including pending late results.

## Validation and ongoing work

Focused156 cases pass, including restored populated/empty endpoints, local
history during expired credentials, cancellation, true tab access, saved empty
inbox refresh, calendar boundaries and timezone rollover. Screenshot checks cover
375dp portrait, dark2x text,760×375 landscape and reduced motion. The following
artifacts were generated and actually viewed:

- [Light chat](images/chat-experience/chat-bubbles-light.png)
- [Dark large text](images/chat-experience/chat-bubbles-dark-large-text.png)
- [Landscape](images/chat-experience/chat-bubbles-landscape.png)

UI UX Pro Max supplied applicable Compose lazy-list/stable-key guidance. Its
targeted bottom-navigation search returned web guidance even after a narrower
retry, so that choice uses the skill's general five-tab navigation rule.
Compose component/testing skills guide caller placement and focused state-driven
semantics/screenshots. These are HRIS feature changes; no generic Starter port.

Recipient durable-delivery/read acknowledgements, preview/unread inbox projection,
cross-company conversation ownership and demo-account provisioning remain separate
active work. A check mark here means server acceptance only. Calls/video/meetings
and the rest of the communications PRD remain active; this increment does not
accept any whole feature group. Full root/Wasm/Android/proof checks pass530.774s; see the
[measurement](measurements/chat-experience-2026-11-03.json). Release26 and its
minified smoke/install are the next step.
