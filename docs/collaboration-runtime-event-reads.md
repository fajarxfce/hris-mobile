# Exact events through the installed native runtime

`CollaborationEventRepository` reads one physical event through the currently published
Company runtime. `CollaborationSpaceEventRepository` reads the provider head or one event
through an exact installed Space operation. Both bind to the existing runtime repository;
unsupported hosts return `Unavailable` and preserve cancellation.

The event snapshot retains the physical owner/device/room, separate membership and encryption
facts, and the classified event. A Space wrapper adds logical runtime ownership without
relabeling the original physical identity. These are acquired facts: current content admission,
original/edit retention and traversal progress still belong to the owning usecase.

`AndroidCollaborationEventReads` owns a finite child operation under the existing native
parent. It registers before raw I/O and maps inside `safeAndroidCollaborationCall`. Each call
has a separate atomic result capture. Rejected, malformed or foreign event coordinates cannot
escape as a successful snapshot. Credential failures are checked before and after acquisition;
repository publication also rechecks the same runtime instance. Authoritative denials survive
simultaneous shutdown or I/O failures.

Cancellation and wait expiry do not orphan late native I/O. The parent's existing child
registry retains capacity and waits for resource disposal before releasing its client/store.
The raw datasource closes its own room handle; this operation starts no client, timeline,
sync, room join, writer, send or automatic retry. SDK-internal HTTP behavior is measured
separately; one SDK call is not a claim of one HTTP request.

The regression cases exercise independent concurrent reads, pre/post-I/O denial, cancellation,
wait expiry, parent shutdown ordering, mapping failures and unsupported platforms. Host FFI
handles establish ownership behavior without loading the native library. Actual encrypted
original/edit/redacted-target/missing-key equivalence and the edit-at-head ACK recovery case
remain the next SDK6 proof. This increment does not activate incoming product routing.
