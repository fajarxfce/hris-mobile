# Chat presentation

`features/collaboration/presentation` owns Chat state, events, effects and plain
Fluent pages. The initial inbox reads the encrypted company conversation directory.
Application service entry, participant selection, device setup, thread composition
and owned delivery scheduling remain the next integration work. This module alone
does not expose a Chat destination in the application or accept a PRD feature group.

## Inbox lifetime

A route enters/stops its ViewModel through typed events. Entering twice acquires one
page. Stopping cancels the finite remote request and retains the local observer so
account replacement and confirmed company denial also clear a hidden destination.
Clearing the ViewModel releases that observer. A late cancelled network result cannot
publish. Ordinary authority age and network failure leave owned cached rows visible.
An explicit successful refresh reopens local observation, including after an upstream
storage subscription completed with a failure; local emissions never start a retry.

The local encrypted cursor and remote directory cursor stay separate. Browsing local
pages performs no network I/O. At the end of acquired rows, one Next action requests
one server page and advances only after admitted success and its local publication.
The observer and request may complete in either order. Previous-page cursors belong
to this ViewModel and are bounded at 200 pages; no silent unbounded directory fetch
runs. Refresh returns to the first local page without blanking an already visible
first page. Selection is accepted only from the current owned page.

## Layout and access

Rows use a single clickable name/avatar action, stable conversation IDs and Fluent
colors. Decorative initials are excluded from accessibility semantics. The caller's
modifier reaches the root row. The list is capped at 720 dp and scrolls independently
of its header. Header actions have 48 dp targets; New chat uses an Add icon with a
localized accessible label so large text leaves space for the title. Empty state,
loading and failed-refresh feedback are separate; saved rows remain actionable when
a refresh fails. Confirmed restriction clears the rows and disables starting a chat.

Common ViewModel tests cover cancellation, duplicate entry, local paging, one-action
remote paging, expired live authority, retained cache, access loss and completed
storage observation. Desktop Compose tests exercise narrow Indonesian text at 200%,
fixed header actions while scrolling, retry behavior, initial loading and silent
avatars. Their screenshots are generated under the module's
`build/reports/screenshots`; the large-text title was reviewed and corrected after
an initial screenshot showed wrapping. Reviewed images: [ordinary text](images/chat-inbox/chat-inbox-english.png) and
[Indonesian at 200%](images/chat-inbox/chat-inbox-indonesian-large-text.png).
[Validation](measurements/chat-inbox-2026-11-01.json) records sources, attempts and artifacts.
These are rendered test states with fictional
people, not evidence of a deployed chat service or device interoperability.

## Next integration

Add owned participant discovery and exact acquisition of the selected conversation,
then setup recovery and a thread joining canonical history with durable pending
messages. Keep confirmed outcomes recoverable from storage; retries must reuse their
operation identity. Observe local ownership without interpreting credential age as
loss of cached facts. Only expose the service after its destinations are connected;
continue all rich messaging, backup, native call and meeting phases in the accepted
Platform PRD. HRIS labels and business navigation stay out of the generic Starter.
