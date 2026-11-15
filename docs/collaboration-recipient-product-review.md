# Recipient product integration

The recipient feature route, local editor, message projection and shared navigation ownership
are implemented and tested. They are not yet activated in the application. Group remains OFF,
V117–140 remain undeployed and all 54 PRD acceptance groups remain OPEN. Use
[the current handoff](agent-handoff.md) for the latest accepted commits and active work.

## Accepted building blocks

[Recipient navigation](collaboration-recipient-navigation.md) uses distinct peer and canonical
targets in the same three-idle-store Group owner. Actual route tests cover warm reopening,
independent active entries, local Send/Back, Copy cancellation and root readmission. A peer ID
is never used as a canonical conversation ID. The Group root remains outside Company scope.

[The thread consumer](collaboration-recipient-thread.md) joins independent local source,
destination, history and progress streams under one request identity and one serial editor.
Any closing denial fences older readers; disposal clears private state. Explicit Send retains
its original independently of later typing. [Stable rows](collaboration-recipient-rows.md) keep
visual identity and order across canonical association and exact provider acknowledgement.

[The page](collaboration-recipient-page.md) renders retained bubbles, quotes and input. Long
press opens bounded multiple selection; [Copy](collaboration-recipient-selection.md) confirms
current local context immediately before clipboard I/O. [Back](collaboration-recipient-departure.md)
flushes local editing, not network delivery. Navigation owns effects and newest-row placement.
Do not expose reply/forward/delete/attachment controls without their durable operations.

## Remaining product work

1. Own the selected contact's public name locally. `DirectoryRepository.observeContact` is the
   exact existing source; `ReadCollaborationSpaceContacts` is remote. Reuse exact-peer validation
   and current session/admission checks. Keep labels in the recipient request lifetime, retain
   admitted labels through transient failure, and clear them on denial/disposal. The current
   contact-use-case prototype is uncompiled and unapplied; it has no consumer or tests yet.
2. Connect the actual application's contact and inbox routes to the same Group root/cache. Keep
   tab/search state and caller-owned Company filters. Preserve the user's four-tab layout and
   contact filter beside search; no repeated API acquisition on passive room entry.
3. Resolve canonical versus recipient drafts and global inbox identity. Both drafts can exist
   independently after an original transfers. Never overwrite later typing, drop a draft on
   source retirement, append a separate temporary inbox, or show two rows for one DM. Preserve
   pending previews, recency, paging anchors and stable row keys through handoff/restart.
4. Own finite conversation preparation in the app after an explicit durable Send and current
   discovery. The existing preparation use case can reuse Open commands or immutable metadata;
   it currently has no Group application owner. Bound/deduplicate peers and readiness inputs.
   Passive opening, typing and ordinary admission renewal must not prepare transport. Existing
   delivery owns remote setup afterward; offline Send must retain immediately.
5. Add remaining recipient content operations with durable intent and exact context. Current
   recipient drafts are plain text and do not support outgoing reply/forward/delete. Keep the
   composer-only historical ViewModel as a control until equivalent root tests justify retiring
   it; never bind it as a second editor to the same route.

## Bounded inbox option to evaluate

The raw store supports coherent multi-query pages and multi-replica mutations. A candidate is
a distinct recipient-row tag in the existing inbox partition, sharing the durable ordinal.
One combined query could read at most `limit+1` canonical and `limit+1` recipient rows and merge
those bounded windows, preserving old canonical tags/formats. This is an unaccepted design
option, not a schema migration or completed implementation.

Resolve canonical handoff and independent drafts before choosing that option. The durable
peer/canonical association already exists; do not create a duplicate table or infer it from
display names. Require exact cursor semantics and atomic source/index publication coordinated
by use cases. Repositories do not call other repositories. Do not load all 5,000 contacts or all
draft peers to merge inbox pages.

## Acceptance before activation

Exercise real application navigation, offline Send/process restart, rapid ordered messages,
source retirement, independent later typing, account replacement and changed paging anchors.
Verify that passive entry/typing/readmission does not repeat setup. Keep rendered gesture,
keyboard, reduced-motion and screenshot checks, browser behavior and Apple source checks
separate from physical-device latency and native runtime evidence. Reusable foundation fixes
must also pass independent Starter validation.

Live Group rollout has its own migration, ownership, recovery and provider gates. Preserve
records and keys. The development phone is install-only and the next delivered version must
exceed 93. No test-only APK or source checkpoint is a new product release. See
[projection and setup boundaries](collaboration-recipient-projection-review.md).
