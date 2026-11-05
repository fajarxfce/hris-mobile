# Incremental conversation metadata

The native HTTP adapter, response mapping and encrypted format2 list storage are
implemented and locally accepted. The Chat list still uses its previous acquisition
path; the Sync use case and screen activation are next. Platform d03fb472 is not
yet deployed. Matrix continues to own message synchronization.

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

## Next activation work

The use case must enforce ordered bounded phases and monotone metadata facts,
catch up after bootstrap and publish replacement only at a completed changes
boundary with pending=false. Expired/scope-reset cursors start staging while the
owned view stays visible. Native access rotation retains a stable parent; a new
parent starts a new remote continuation. Duplicate references must fold safely;
older/equal contradictory revisions cannot replace newer facts or inflate counts.

Scope and confirmed admission must be checked around every remote/storage step.
Denied access stays gated through cancellation, failed cleanup and retry; only an
admitted completed acquisition may reopen its observed gate. Execution, retries,
network waits and lifecycle observations must remain bounded and owned.

ChatInbox must consume local pagination and bounded sync progress, keeping remote
opaque cursors out of UI UUID selection. Preserve same-owner previews and existing
detail observers on unchanged content. Add use-case/ViewModel/AppFlow coverage,
full app and Apple gates, deployed-server/native offline proof, and a later
validated phone release. The generic raw-storage Starter port follows that Mobile
acceptance; format2, metadata transport and HRIS admission remain in Mobile.
