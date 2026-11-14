# Group root composition and retained contact layout

SpaceChatRootContent exposes a handle for the current admitted account/Group store. It observes
the distinct store lifetime, resolves the bounded thread graph in that store, and keeps the
Company subtree free to change without taking ownership of Group models. A retired handle cannot
create another graph. This is a composition entry, not an application route activation.

Visible-thread preparation now belongs to both the graph and its composing screen. Graph closure
cancels it even while an outgoing composition remains mounted; composition disposal cancels and
joins its child independently. Raw graph APIs still reject access after closure. Inbox/thread
entry points and queued navigation/clipboard effects reject retired owners before acting.

SpaceContactsPage reuses ChatPersonRow and a stable-key local list. Its caller supplies the compact
search/filter region. Retained contacts remain visible on transient failure; obsolete search
results are not selectable. Explicit denial replaces the list. The page preserves visible contact
identity after a local row is removed. Three recorded/inspected desktop images cover phone,
landscape and dark 200% text. Long names wrap and company labels ellipsize without overlap; the
filter stays beside search with a 48dp target. Images are fixture evidence, not phone acceptance.

Nine new desktop cases cover actual root/NavHost/Koin ownership, outgoing disposal, list behavior
and visual interaction. Existing root/graph and first-destination-frame tests remain. The focused
27-case run passes and its images match the independent inspection. Exact full/platform results,
source/report hashes and unsuccessful earlier test attempts are retained in the linked JSON.
The test-only route visibility and Main-thread semantics corrections do not disable lifecycle
checks or weaken the stale-open assertions; composition now unmounts before its parent teardown.

The combined product hub is not activated yet. Keep an independent browse projection while a
separate search route edits its query. Bind app entries to the Group handle, keep AppViewModel at
its window owner, and resolve complete public company membership before company filtering.
Contact entry must distinguish peer ID, pending Open intent and canonical conversation ID; retain
new-chat drafts while setup is incomplete. None of those identifiers are interchangeable.
GroupOFF, V117–140, current live queues and phone92 remain unchanged. All54 PRD groups remain OPEN.

[Exact validation](measurements/collaboration-space-root-composition-2026-11-14.json).
