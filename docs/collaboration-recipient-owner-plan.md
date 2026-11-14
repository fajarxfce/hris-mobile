# Recipient transfer owner — next implementation

This is a plan, not an active worker or a completed product route. The accepted per-original
transfer operation is the prerequisite. Keep Group rollout OFF until its separate product and
live gates pass.

## Separate original changes from typing

Retain one encrypted source-version document in the existing recipient partition. Its identity
must be disjoint from peer UUIDs and `submission:` IDs. Handoff and conditional source removal
advance it in the same raw transaction; editor-only writes preserve its logical value. Tagged
outbox queries and the128-original quota must exclude it. Keep the existing checkpoint format.

Initialize an explicit empty marker when creating a new editor-only partition. Otherwise an
empty new partition would look like legacy storage and every keystroke could wake recovery.
For a legacy missing marker, authenticate the current checkpoint and use an explicit initial
generation/revision fallback so old originals cannot be missed. The first editor upgrade must
preserve that logical source version; source mutation advances it. Exact handoff replay must
preserve both wake and stored facts instead of writing just to upgrade metadata.

Writers should read the marker and their exact expected IDs in one bounded raw snapshot/CAS,
reject staging and authenticate retained frames. The existing outbox handoff reads peer/source;
draft replacement currently reads one peer; removal reads one source. Update those specific
boundaries without repository-to-repository calls. A new marker codec owns serialization and
cryptographic mapping. Missing keys never authorize replacement keys over retained ciphertext.

Expose one admitted source-version observation that reads only bounded metadata and deduplicates
its logical value. Keep draft get/observe as exact-peer reads. Typing must not read every original,
invoke transfer or schedule transport. `ReplicaStore.changedDocuments` is an available alternative,
but scanning all editor deltas adds avoidable work when only an original-change signal is needed.

## Own a finite local pass

Add a repository-coordinating domain operation for admitted pending-page reads. Validate each
page's cardinality, unique ordered IDs, forward cursor and lookahead. Keep1..8 originals per read
and a maximum16 pages per pass under the128-original quota. New data arriving during a pass
belongs to a coalesced follow-up pass; invalid/no-progress cursors must fail instead of looping.

The app worker invokes the pending-read and per-original-transfer use cases. It owns subscription,
cursor, cancellation and bounded attempt state; authorization and transfer policy remain in
domain use cases. Put the relevance/deduplication decision in a named pure domain policy, rather
than scattering business retry rules across worker helpers.

Observe latest source version, canonical catalog and inbox destination under the same local
account/Group lifetime. Do not `collectLatest`-cancel a transfer when its own successful removal
updates the source marker: restarting from the first blocked peer on every removal creates
unnecessary repeated work. Finish a finite pass, yield between pages and then service changed
inputs. The outer account/Group owner must still cancel and join immediately on replacement.

Retain only bounded immutable waiting facts. WaitingForConversation depends on relevant catalog
changes; WaitingForInbox depends on the destination. Successful removal of another original must
not continuously retry an unchanged blocked peer. Failures require an explicit changed input or
replaced owner, not an age timer or endless self-wake. Keep memory bounded to the source quota.

Compose this worker beside the existing inbox repair worker under
`ApplicationCollaborationInboxOwner` and its data-module/app DI ownership. Registration starts
no collector. This local work must run on Web as well as Android/iOS, independently of native
Matrix parent/device readiness. The existing command observer seeds a newly retained canonical
original; later inbox acknowledgement permits source removal. That feedback must settle to idle.

## Evidence required before activation

Test legacy initialization/upgrade, editor-only writes, source mutation, malformed metadata,
key/account isolation, conflicting writers, lost responses and cancellation around commit with
real storage. Verify unaffected editor/other-original ciphertext; distinguish the intentionally
changed marker explicitly instead of weakening byte comparisons.

Test a first blocked peer followed by ready peers across pages, rapid new originals, relevant
dependency changes, own-write feedback, empty/unchanged inputs, denied access, replaced ownership,
late results and stopped observers. After settling, assert no additional transfer/probe/removal
invocations without a changed fact. Validate actual owning DI and desktop/browser behavior;
compile Apple source and keep actual native/device results separate.

Then continue pending-recipient list rows, Send gesture ownership, new-contact routing, complete
company membership filters and explicit Group product/live rollout. This worker alone does not
complete the54 communication PRD acceptance groups.
