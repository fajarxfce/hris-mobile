# Notification source invalidation

The application can now subscribe to advisory changes for a captured native parent,
company and conversation. The stream carries no message bodies, previews or actions.
A successful Unit value means that the owner must revalidate its current card; it is
not permission to publish one. The host lifetime itself remains to be connected.

StoredCollaborationNotificationChangeRepository observes the published metadata of
five exact existing partitions: company conversation list, company self identity,
conversation history, notification preferences and private read state. Initial snapshots
are included. Staged metadata stays excluded until publication. Distinct raw checkpoints
prevent unrelated database writes from producing source changes; Unit output deliberately
is not deduplicated. Publication journal acknowledgments, action envelopes, reply storage,
traversal progress and other companies/conversations are outside this subscription.
The repository reads no document bodies or encryption keys, and adds no stored schema.

ObserveCollaborationNotificationChanges binds that subscription to the actual native
parent and the specified company's existing read admission, including its buffered
confirmed denials. It does not depend on the selected company or acquire live credentials.
A denial or parent replacement cancels and joins the source subscription. Every source
signal checks retained admission and the current parent again within eight seconds.
An already known denial returns immediately without waiting for a pending session read;
that Failure has no contents or authority. Cancellation propagates. Normal completion or
failure of any watched source/session/admission flow closes the observation; there is no
implicit restart. A new admitted gate may establish a new source subscription.

Eleven domain cases exercise ownership, expiry/selection independence, repeated Unit
signals, invalid/foreign input, denial and recovery, late reads, finite deadlines,
cancellation and completion. Six common storage cases execute on SQLite and IndexedDB;
they intentionally use synthetic metadata to prove advisory signaling without decryption.
Three actual application/Koin/SQLite cases use fictional acquisition and a fake OS:
publication ACK exclusion, changed preferences, private read coverage, original redaction,
and joined company denial across container reopen. They do not activate the native host
or remove a displayed OS card; the tests explicitly distinguish those responsibilities.

The next application owner must track at most 64 opaque references, register and join
per-card observations, revalidate initial subscriptions, and preserve bounded dirty work
while the nonwaiting native surface is busy. Source-stamp/privacy changes may need a
silent rerender. Confirmed read/mute/redaction/denial requires exact current retirement;
a different suppressed candidate must not remove another current card. Transient failures
must close native display without inventing permanent delivery/retirement success.

MaintainCollaborationRuntimeOwnership already buffers company restriction before updating
runtime retention. Its processed parent is resource ownership, not company permission;
per-company admission observation remains necessary. Native callbacks, authenticated
old-family input review and notification-specific process-death proof remain separate.

See [recorded checks](measurements/chat-notification-invalidation-2026-11-03.json) and
[remaining host work](collaboration-notification-publication-plan.md).
