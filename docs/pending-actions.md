# Pending actions

Account opens **Pending actions** for the selected company. The page observes local
Inbox actions, attendance captures, overtime, leave, expenses and document files.
Opening or reloading the summary performs no HTTP request or command delivery.
Each row opens the owning workflow, where normal review, retry and removal rules
still apply. Attendance capture and recovery open Home; file transfers open local
document files.

## Meaning of the counts

- Drafts are edited working copies or complete imported files without an upload.
  Merely opening a new form no longer counts as an edit. Older working copies
  without the edit marker remain conservatively included.
- Ready to send means a retained command has not entered transport. It does not
  promise that current policy will admit delivery.
- Awaiting confirmation means transport may already have committed the command.
  Recovery keeps its original operation; this summary never submits a replacement.
- Needs review counts retained rejections and failed document validation.
- File transfers include unfinished uploads and incomplete local downloads,
  including paused transfers. They do not imply that a worker is running.

Confirmed commands and complete downloaded files are excluded. A working revision
already frozen into a leave/overtime command is counted once through that command.
Expenses compare the immutable saved input, reason and version; unfinished items
or newer edits still count separately. Retained confirmed commands also suppress
their matching draft while the owning workflow finishes cleanup.

## Ownership and bounds

`ObservePendingActions` is a workspace-domain port. Each feature implements its
own policy and binds it in its data module. Home consumes these ports without
calling feature repositories. Encrypted repositories enumerate published working
metadata and retained commands; the raw database classifies no business outcome.

Every projection uses the current API audience, account, company and feature read
admission. Ordinary age or expired credentials do not hide owned local work.
Confirmed access/MFA denial closes the row, including after failed persistence.
Each observer rechecks admission after pending reads. Reloading local data cannot
clear a denial; an admitted acquisition in the owning workflow must do that.

Commands include the entire partition under the database's existing limit of 128
commands per account. Drafts read the first 50 published working slots per source,
with a 51st checkpoint indicating another page. Overtime has separate plan and
action sources. When a source has more slots, the UI explicitly reports a lower
bound and directs the user to its workflow. Imported files retain the existing
bounded file repository contract. No schema migration or plaintext index is added.

The route owns observations. Leaving, opening another workflow, verification and
disposal cancel them and clear displayed counts. A local reload replaces each
reader only after the cancelled reader releases its slot, so delayed storage work
cannot overlap its replacement or publish into a stopped owner. Failures remain
visible; an unreadable row never claims that nothing is pending.

## Regression coverage

Domain regressions cover draft/command deduplication, retained outcomes, document
transfer states, age-independent reads, denied admission, failed persistence and
session replacement during a delayed read. SQLite/IndexedDB contracts cover
published partition pagination, account replacement, cancellation and complete
command capacity. Encrypted draft contracts cover ownership and paging across
reauthentication. Presentation tests cover duplicate navigation, retry, disposal,
non-cooperative storage and narrow layouts at 200% text. An application test edits
a leave draft, reloads this summary without HTTP, and reopens the same input without
submitting a leave request.

## Android acceptance with the real API

On 28 October 2026, Mobile `9a557dcc` and Platform `1f95e0c3` passed the
dedicated Android 35 emulator workflow. A fictional account signed in to an
isolated API/PostgreSQL/worker fixture and opened its assigned company. All six
rows initially showed no pending actions. Opening an untouched leave form did
not change the count; editing its reason produced exactly one Leave draft.

A loopback proxy counted only request methods and sanitized paths. Opening and
reloading the edited summary kept the request counter at 44. After disconnecting
the emulator's API forwarding and force-stopping the application, the company,
one-draft count and exact input were restored offline. Reconnecting and retrying
the owning form preserved the input. Explicitly deleting that draft returned the
summary to zero. Across the complete session, none of 53 native requests wrote
company data; the independent API fixture also found no leave request in its
scheduled fixture date. Sign-out succeeded and the owned services were stopped.

This checks native persistence and workflow navigation for the Leave draft
projection. Transfer delivery, other workflows' command states and physical
device acceptance retain their separate validation requirements. See
[validation](validation.md#pending-actions-on-android) for local evidence.
