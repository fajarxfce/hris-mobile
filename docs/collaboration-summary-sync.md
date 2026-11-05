# Incremental conversation metadata

The Chat list now uses the native incremental feed and encrypted format2 replica.
Local content and pagination remain available independently of remote progress.
The implementation passes whole-app local and retained Android acceptance.
Release40 is installed on the authorized phone; Platform54b10ce8/schema116.1 is
live with its original push settings preserved. Matrix continues to own message synchronization.

Bootstrap and changes use the native authenticated client, exact session/company
scope and a 128KiB response limit. Opaque cursors are escaped as query parameters,
kept unchanged across one native credential refresh, and bounded to 2,048 printable
ASCII characters. Mapping checks phase continuation, page size, ordered identifiers/
positions, nonnegative metadata revisions, owner/company/peer identity, canonical
conversation version0 and server time. Null summaries retain tombstones. Multiple
references to one conversation are valid; no per-row HTTP acquisition is performed.
Stable denial/reset failures and cancellation propagate through safeApiCall.

[Transport evidence](measurements/collaboration-summary-transport-2026-11-05.json):
10 focused desktop cases pass in the first run; the corrected gate passes all617
collaboration browser cases75.003s, zero failures/errors/skips. Android compilation,
format and architecture pass on4,116 unchanged inputs. The initial format failure
from temporary drafts and empty browser selection are recorded without treating
them as successful browser validation. No phone or iOS execution for this adapter.

## Accepted encrypted cache

Format1 facts retain their original key and authenticated indexes while format2
stages. Format2 separates server metadata revisions from local publication and
canonical conversation versions. Its encrypted checkpoint retains native parent,
phase, opaque cursor and progress. Raw versions are bounded to exact IndexedDB
integers; unsupported revisions fail without a write. Hidden tombstones retain
identity and revision. Interactive metadata may insert an unversioned fallback at
version0; a tracked fact or tombstone cannot be overwritten by that fallback.

A shared repository owns both list and sync contracts and serializes local writes.
Working reads include a bounded set of tombstones and their checkpoint. Changed
facts and the next cursor commit atomically against the observed generation and
revision. Empty changes can advance the cursor without rewriting document bytes.
Visible list/detail reads continue using the published generation only.

Interactive merge discards an exact incomplete stage, matches its observed active
checkpoint and stores the new conversation immediately. A late background response
then loses its generation. If background publication wins first, the interactive
caller can retry against the new checkpoint without losing either identity.
Neither staging nor publication clears the independent admission gate.

[Cache evidence](measurements/collaboration-summary-cache-2026-11-05.json):
21 focused desktop and623 browser cases pass168.482s, zero failures/errors/skips;
Android compilation, format and architecture pass on4,125 unchanged inputs. Six
new shared SQLite/IndexedDB scenarios cover old-format/key retention, reopen,
preemption, tracked deletion/fallback, cancellation during encryption, replacement
sessions, concurrent publication and no-op cursor progress. Whole-app/native checks
for the activated consumer are still required. Apple42094694741 targets the earlier
raw storage foundation only; phone39 is unchanged.

## Accepted synchronization use case

[Use-case evidence](measurements/collaboration-summary-sync-usecase-2026-11-05.json):
38 desktop cases pass91.246s, zero failures/errors/skips, with data Desktop/Android
compilation, format and architecture on4,130 unchanged inputs. Seven new pure-policy
cases and nine use-case scenarios cover staged catch-up, bounded pending rounds,
revision contradictions, tombstones/restores, late/cancelled responses, received
denial, lost commit acknowledgements, cursor reset, new native parent, ten-page
yield and thirty-second timeout. The first attempt had one test type-inference
error, corrected without changing policy or expectations.

SyncCollaborationList owns a thirty-second, ten-page acquisition round. It persists
opaque cursor progress after each atomic page, keeps interactive metadata writes
independent of its network lock, and checks owner/admission around remote and local
operations. Replacement publishes only after catch-up reaches a non-pending end.
Confirmed denial remains closed through failed cleanup/cancellation and can reopen
only against its observed revision. Cursor reset is limited to one per round and
the named server reset codes. ChatInbox now invokes this use case while its destination is visible.

## Local inbox activation

ChatInbox observes saved rows first and uses only local UUID cursors for navigation.
Remote opaque continuation stays encrypted in the replica. A visible destination
owns each bounded round and delayed continuation: thirty seconds after catch-up,
server-pending yield or bounded retry after transient failures. Hiding/disposal
cancels acquisition and timers, while owned local admission remains observed.
Paging saved rows stays enabled during network work. A competing metadata write
resumes quietly from durable progress instead of exposing a refresh instruction.

The inbox shares one owned local page stream. History/draft subscriptions depend
on visible conversation identities, so name/checkpoint changes update the page
without restarting healthy previews. Changed IDs, scope and admission still close
obsolete streams. Explicit local-content retry can reopen a failed subscription.
Point/by-room metadata acquisition retries at most one local CAS after background
publication, preserving owner/denial checks and avoiding another HTTP request.

[Activation evidence](measurements/collaboration-summary-inbox-2026-11-05.json)
records focused ViewModel/UI, real Room/HTTP/DI cold-reopen/offline/name/delete/restore
integration, pure policies, scoped cancellation/denial races, browser and root gates.
Initial fixture/clock/lint failures are retained with their corrections. Native
Android and final-source Apple acceptance are still separate outstanding gates.

## Android delivery

[Release40 evidence](measurements/android-release-phone-2026-11-05-build40.json)
records root/browser/Android acceptance, the verified nondebuggable ARM64 APK and
its install-only update on the authorized phone. Retained Rani native tests pass
179.105s: observed HTTP200 changes acquisition while draft previews persist,
compact filters, tab swipes, scroll/FAB transition, offline room/history and cold
offline reopen with contacts retained. Network and temporary viewport were restored.
No new messages, grants or Forward publications were needed. Retained Bima history
also survives in-place update, two room reopens and a cold process restart.

Four screenshots were visually reviewed; six captured states are retained. The
first passive observer missed outgoing request frames due to its protocol filter;
ETH_P_ALL plus strict IPv4/TCP/address/route filters corrected the harness. No
packet bodies or headers were persisted. The APK verifier initially expected joined
URI literals although the code concatenates shared path and suffix; no source or
APK change was needed. These unsuccessful checks are recorded separately.

## Continuing work

[Apple42106835639](measurements/collaboration-summary-inbox-apple-2026-11-05.json)
accepts exact927407ad:1,298 simulator cases and the full Xcode app. The workflow
does not execute collaboration data/presentation tests. Physical iPhone remains waived. The nine
generic raw-store files/tests now match Mobile in Starter, with independent local
and Apple checks still pending. HRIS codecs, transport and admission remain here.
Matrix owns message content. Continue edit/delete-for-everyone, media, groups,
backups, voice/video and meetings under the open communications PRD.
