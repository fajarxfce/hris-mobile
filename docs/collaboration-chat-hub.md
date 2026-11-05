# Chat and contacts hub

Home exposes four root destinations: Home, Chat, Inbox and Account. Chat contains
Conversations and Contacts; the existing employee directory is no longer a separate
bottom-navigation item. The New chat action opens the current cross-company
participant picker. Contacts currently retains the company employee directory,
local name/number search and department selection. Employee rows are directory
records, not yet account-resolved direct-chat actions. Call history will share this
hub when the call workflow is implemented; no inactive Calls tab is shown.

Home owns the selected communication section. The hub has no title app bar or
header actions: fixed Conversations/Contacts tabs sit directly above its pages.
The New chat action is a 56dp floating button at the lower right. Consumed vertical
scroll hides it after 12dp downward travel and restores it on upward travel;
horizontal gestures do not toggle it. Its short fade/slide is interruptible, honors
reduced motion and disables clicks immediately when hiding. List bottom padding
allows the last row to scroll clear of the button.

Tabs support both taps and horizontal swipes. App composition provides the two
feature entries; Home never imports their ViewModels. The entries retain separate
route-owned state and list positions. Only the settled page starts acquisition and
consumes navigation effects; prefetched neighbours remain idle. Existing local
access observers still clear denied content while hidden. Explicit tab selection
can interrupt a gesture, and a new gesture can interrupt the tab animation.
Inactive settled pages are excluded from the accessibility tree.

Contacts and its dedicated search page place a 48dp department-filter icon directly
to the right of the search field. A selected filter changes the icon treatment and
accessible state description; its full name remains in the filter dialog. There is
no separate filter row. Dedicated search keeps its own Back action and query owner.
Legacy saved HomeTab.Employees values normalize to Chat/Contacts. System Back from
the root hub returns Home; inner search and conversation routes return to their
retained parent. Account/company changes dispose the old owners.

## Stable local conversation previews

The encrypted local list is the UI source. Names may arrive before the bounded
one-message/draft reads. An explicit previewsLoaded field distinguishes that partial
emission from an authoritative empty result. Presentation retains previously loaded
previews only for the same exact local access owner and still-listed conversation
IDs. Complete emptiness, read failure, removed rows and owner replacement remain
authoritative. Confirmed access denial still closes the projection immediately.

Identical local list emissions are filtered before opening the detail subscriptions.
Actual history/draft updates still publish through those existing subscriptions.
Hiding the section stops optional detail work without clearing same-owner previews;
returning resumes local reads. An explicit successful acquisition may still restart
observation to recover a storage flow that completed after an exception.

Merging unchanged remote metadata performs no encryption, record publication or
checkpoint rewrite. A final scope/checkpoint comparison still rejects concurrent
session replacement. Changed peer labels write only changed records. The first
empty acquisition still establishes a checkpoint. This does not change the transport:
the HRIS summary endpoint is still paged metadata acquisition, not an incremental
change feed. Matrix message transport already uses incremental sync. HTTP metadata
deltas and tombstones remain required follow-up work; neither no-op writes nor UI
retention are evidence that this transport gap is complete.

A successful Next acquisition resolves against its committed local page even when
no new metadata was written. An empty response cannot leave a pending navigation
intent that later reacts to an unrelated incoming row. Newly acquired next pages
still open without requiring a second tap.

## Validation

The compact swipe/FAB/filter increment passes root checks, Android compilation and
host tests, browser data/presentation tests and the native proof application's
build/tests:3,088 fresh cases453.069s, zero failures/errors/skips,4,108 unchanged
inputs. All90 application-navigation cases pass, including inactive Contacts
prefetch without acquisition and repeated section returns without duplicate reads.
Seven generic cases cover interrupted selection/gestures, disabled/reduced-motion
behavior, draft retention, accessible filter state, exiting-action input and scroll
visibility reset. Seven current captures were opened/reviewed at compact, dark200%
text and landscape sizes. [Evidence](measurements/chat-hub-controls-2026-11-05.json)
retains the initial selector/architecture failures and passing correction.

Release39 is installed in place on the authorized phone, with exact artifact bytes
verified and existing identity/data retained. Its actual Android proof passes in
156.226s: both filter surfaces, swipes, fixed tabs, floating-action scroll behavior,
retained drafts and cold offline reopening. Six native captures were reviewed.
Bima retains eight visible rows through four update/reopen/restart checks109.519s.
The first native attempt expected scrolling where all five contacts fit; the final
driver temporarily narrows only the owned emulator viewport and restores it after
the gesture. Product source and APK are unchanged. No phone UI was driven.
[Release39 evidence](measurements/android-release-phone-2026-11-05-build39.json).
Release38 evidence below records the earlier hub acceptance.

Focused navigation, persistence and preview recovery passed 51 cases/136.104s with
4,103 unchanged source inputs. Six generated desktop captures were opened and
reviewed; they are visual-review evidence, not pixel-baseline comparisons. The first
47-case run failed formatCheck because an unfinished screenshot fixture under .work
was included; that fixture is now a real desktop test. The first full gate executed
4,025 cases/946.818s with one 15-second login UI timeout before the overtime case
entered its workflow (zero errors/skips); it is not a passed gate. Final full2 PASSES root check, Android host/compile, browser storage/presentation
and native proof build/tests: 1,045 fresh cases/399.451s, zero failures/errors/skips
and 4,103 unchanged inputs. All 90 application navigation cases pass, including the
previous login timeout. The new empty-Next regression passes on desktop/browser.
[Evidence](measurements/chat-hub-2026-11-05.json) records both failures and recovery.
Non-debuggable ARM64 devRelease 38 from source `34e68b12` is installed on the
authorized phone. Its bytes match the validated artifact; the update preserves
the app identity, data and first-install time. No phone UI was operated.

The same APK passed the owned Android hub proof in 98.877 seconds: section
navigation, return from New chat, both retained draft previews, room history,
offline reopening and a cold offline process restart. An independent HTTP probe
confirmed the server was unreachable during the offline assertions. Contacts
also remained available after that restart. Four actual Android captures were
opened and reviewed. This proves retained local behavior, not completion of the
metadata delta endpoint. [Native and delivery evidence](measurements/android-release-phone-2026-11-05-build38.json).

The fictional Rani fixture now has `people.directory.read` alongside
`collaboration.chat`, granted through the ordinary administrative API. The normal
company selector refreshed its workspace permissions before acquisition. Its
native account/device, keys, drafts and messages were preserved; other memberships
were unchanged. A previous access denial was not bypassed for the proof.

Test coverage includes delayed detail reads through stop/reopen and identical
refresh, denial during a pending read, exact-owner isolation, authoritative empty
previews, unchanged ciphertext/checkpoints, changed-row-only writes, and session
replacement at a no-op commit boundary. The UI fixture uses synthetic names and
captures compact, dark 200% text and landscape layouts with reduced motion.
