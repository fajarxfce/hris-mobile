# Chat and contacts hub

Home exposes four root destinations: Home, Chat, Inbox and Account. Chat contains
Conversations and Contacts; the existing employee directory is no longer a separate
bottom-navigation item. The New chat action opens the current cross-company
participant picker. Contacts currently retains the company employee directory,
local name/number search and department selection. Employee rows are directory
records, not yet account-resolved direct-chat actions. Call history will share this
hub when the call workflow is implemented; no inactive Calls tab is shown.

Home owns the selected communication section. App composition provides the two
feature entries and a shared whole-header slot; Home never imports their ViewModels.
The entries retain separate route-owned state, list positions and lifecycle work.
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
Native acceptance and release 38 delivery remain pending.

Test coverage includes delayed detail reads through stop/reopen and identical
refresh, denial during a pending read, exact-owner isolation, authoritative empty
previews, unchanged ciphertext/checkpoints, changed-row-only writes, and session
replacement at a no-op commit boundary. The UI fixture uses synthetic names and
captures compact, dark 200% text and landscape layouts with reduced motion.
