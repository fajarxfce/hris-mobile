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

## Android recovery after a committed command loses its response

On 29 October 2026, the dedicated Android 35 emulator also passed an Inbox command
case against the real isolated API/PostgreSQL/worker. The proxy dropped the first
successful Mark as read response after server commit and refused later native
requests until explicit transport restoration. The detail retained Retry and the
summary showed **Awaiting confirmation: 1**. Opening/reloading the summary kept
the request counter unchanged at 30. Removing forwarding and force-stopping the
app preserved the company, pending count and original action through offline startup.

After restoring transport, Retry reused the same operation/input. The independent
server observer found exactly two forwarded read commands, a single resulting
version increment to 1, a read timestamp and no acknowledgment. All six summary
rows then showed no pending actions; sign-out returned to login. Across 42 native
requests there was no other company write. This expands native evidence to an
unconfirmed command, without claiming all transfer/feature outcomes were exercised.

The [tracked Platform fixture and steps](https://github.com/fajarxfce/hris-platform/blob/feat/firebase-auth/docs/native-acceptance.md)
passed in 6.0 minutes (6.4 including setup). The dev APK built in 45 seconds using
the loopback override; application source was unchanged. Owned services, private
connection files and forwarding were removed, and the emulator stopped without
wiping data. See [validation](validation.md#inbox-command-response-loss-on-android).


## Android partial-download recovery

The actual API/PostgreSQL/worker/Garage and Android 35 case passed on 29 October
2026. A one-MiB partial download remained **File transfers: 1** after offline
process restart, and Open Documents reached its retained local file. Summary
open/reload made no request (31 → 31). Reconnection and Resume acquired only the
remaining ranges; the native saved copy matched all 3,145,841 source bytes and
SHA-256. All six summary rows then became empty and sign-out succeeded. Across
49 requests there was no native company mutation. Source inspection metadata was
fictional; transfer bytes and API/storage/native actions were real.

See [validation](validation.md#partial-download-recovery-on-android) and the
[tracked fixture](https://github.com/fajarxfce/hris-platform/blob/feat/firebase-auth/docs/native-download-acceptance.md).
Application code and Starter required no change. Owned fixtures and forwarding
were removed, the created public copy was cleaned, and emulator app data was retained.
