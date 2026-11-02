# Conversation notification settings

Each acquired direct conversation has local mute and preview preferences bound to
the HRIS audience, account, company and conversation. The defaults allow alerts
while hiding names and message content. Mute supports an absolute expiry or an
indefinite choice; preview supports hidden content, sender only or message content.
An expired mute remains stored and is evaluated against the current clock. Reading
settings does not create a key or rewrite an expired preference.

`ReadCollaborationNotificationSettings` and `UpdateCollaborationNotificationSettings`
use retained local ownership and the acquired conversation binding. Ordinary token
expiry and the absence of a selected company do not prevent offline access. A
confirmed content denial closes the operation even if a pending storage/session
read also fails. Old session bindings cannot act for a reauthenticated account;
the current session can still recover that account's encrypted preferences.

A change carries a stable operation UUID and the observed generation/revision.
The current identical operation is recoverable after a lost commit response;
an older operation cannot overwrite a newer choice. A conflicting operation ID,
stale observed version, malformed data or storage failure requires explicit handling.
There is no hidden retry or fallback to unmuted defaults after a failed read.
The updater has a single non-queued invocation and a ten-second operation budget.
Cancellation joins pending work; a completed write remains owned and recoverable
even when its caller can no longer publish success.

The existing history content key encrypts one checkpoint under
`collaboration-history/notifications/{company}/{conversation}`. Its AAD binds the
audience/account/company/conversation and generation/revision; settings, operation
ID and mute deadline never appear in plaintext replica indexes. The existing key
absence guard also covers inactive accounts in this namespace. Missing or corrupted
keys never trigger a reset over retained ciphertext. The payload is bounded to
4 KiB plaintext and 8 KiB encoded ciphertext. No database schema, key format or
backup-exclusion change is required.

The owning data module supplies the repository and use cases through the assembled
application graph. This increment supplies persistence and policy. System
notification projection, UI controls, existing-notification replacement/cancellation,
native actions and inline reply still need integration. Mute does not cancel message
delivery or history recovery. These preferences do not enable public read receipts,
alter the read-intent queue or activate live chat push. The existing private viewport
receipt behavior remains separate.

[Validation](measurements/chat-notification-settings-2026-11-02.json): ten domain and
nine actual SQLite cases plus two assembled-container cases pass232.667s. Full
root/proof/Wasm/browser passes400.043s:3335 desktop,230 Android host,10 build-logic,
six proof-host and19 browser cases, including explicit task reuse;1775 cases in228
suites freshly executed, no failures/errors/skips. Browser includes the same nine
IndexedDB persistence cases and ten domain cases. Initial two fixture-only failures
and corrections are retained. These checks do not accept native notification display.
