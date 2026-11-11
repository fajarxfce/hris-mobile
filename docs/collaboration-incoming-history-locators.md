# Local incoming history locators

Recovery can enumerate published Company or Space history partitions locally, including
every retained device. Separate repository overloads preserve the two namespaces. The
existing account-scoped metadata transaction excludes staging snapshots and uses an
exclusive partition cursor. No second history catalog is introduced.

The repository reads at most 17 metadata rows and consumes at most 16. Its data mapper
validates exact scope, publication, ordering, coordinate shapes and the lookahead row.
Root and provider-page partitions consume cursor space without becoming incoming
locators; an empty locator page can therefore have a continuation cursor. Deleting the
previous cursor does not prevent the next seek. Insertions behind it require a later
pass, which remains the recovery owner's responsibility.

No history content or encryption key is opened, and no native client, transport,
mutation or wake is created. An inactive account scope returns the raw store's empty
view. That view is not proof of incoming completion: consumers must keep admission,
ownership and cancellation checks around suspended reads. A locator also grants no
room identity, pending-reference resolution or content authorization.

Local acceptance covers 12,688 cases. Twelve new common mapper cases and fifteen
actual metadata-storage cases cover namespace/account isolation, all devices, staging,
reopen, reauthentication, removed cursors, insertions, query bounds and cancellation.
The app's existing DI graph resolves the new repository. These metadata fixtures use
opaque payloads; they do not establish content decryption or native SDK behavior.

The first focused run failed three new inactive-scope expectations. Existing raw DAO
and contract behavior requires an empty view, so the tests now assert that exact view
and successful restoration. Production behavior and case identities were preserved;
the failed source and reports remain in the validation workspace.

Keys-only recovery, active/requested passes, fair scheduling and native allocation
costs remain separate work. Raw observation and Original producer remain disabled.
No phone, Starter or live-server behavior changed here. All 54 PRD groups remain open.

[Exact validation evidence](measurements/collaboration-incoming-history-locators-2026-11-11.json).
