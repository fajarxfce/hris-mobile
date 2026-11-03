# Observing notification settings and private reads

Notification settings and read-state repositories now expose cold owned storage
streams. Each subscription follows its exact encrypted checkpoint and shares the
same decoder as the finite read operation. An empty partition returns the existing
default state without obtaining or creating a content key. No network acquisition,
credential renewal or native notification operation occurs.

Read-state observation captures a copy of zero through 128 unique event IDs when
collection starts. Its coherent snapshot includes exact selected-event coverage,
pending private receipt intents and the current acknowledgment. Later caller-list
mutation cannot change an existing subscription. Settings observation returns the
current mute/preview choice and version. Callers still own company admission and
must revalidate before using either value to publish or retire a notification.

Both streams map acquisition and decoding failures through safeStorageFlow. A
malformed checkpoint produces a Failure value; a later valid checkpoint can recover.
An upstream exception produces Failure and completes without restarting. Identical
mapped states are suppressed, cancellation propagates, and the collector joins
pending mapping when cancelled. The zero-buffer boundary does not turn a stream
into a durable queue or an authorization grant.

The existing contract suites now contain twelve settings and fourteen read-state
cases, running against actual encrypted SQLite and IndexedDB. Six new cases cover
subscription defaults, saved updates and reopen, fixed event selection, receipt
acknowledgments, malformed and duplicate emissions, failed upstream completion,
pending mapping cancellation, and invalid selection before storage subscription.
The unrelated-write case uses a fictional independent checkpoint; full publication
ACK exclusion and application notification lifetime remain separate integration work.

History/list/identity changes, buffered company denials and focused navigation must
also invalidate native cards. The application must cancel/join those registrations,
revalidate after first subscription, keep bounded opaque pending work when busy, and
avoid private preview queues. These repository streams do not activate that host.

See [recorded validation](measurements/chat-notification-source-observation-2026-11-03.json)
and the [host integration plan](collaboration-notification-publication-plan.md).
