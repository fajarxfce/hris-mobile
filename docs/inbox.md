# Employee inbox

The Inbox screen reads account-owned correspondence from HRIS Platform. The
company must grant `announcements.read`, and the backend must configure its sync
cursor keys and run the publication worker. A GET does not mark a message read.
Read and acknowledgement are explicit employee actions. Push-triggered navigation
remains subsequent work.

## Ownership

- `core/sync` implements the explicit collection feed, raw API/datasource
  contracts, DTO validation, and HTTP failure mapping.
- Inbox repositories fetch canonical messages and coordinate encrypted replica
  storage. Datasources never call each other or choose access/retry policy.
- `SyncInbox` owns scope checks, staging, hydration, pagination, reset policy,
  and execution bounds. `ObserveInbox` owns local admission and expiry.
- `PrepareInboxAction`, `ReplayInboxAction`, `ObserveInboxAction`, and
  `ReviewInboxAction` coordinate repository contracts and own command admission,
  retry, observation and resolution. Use cases never call one another.
- Route-owned MVI ViewModels observe the database and invoke use cases. Pages
  render state and forward actions; refresh preserves existing content.

Each run explicitly selects `INBOX`, reads at most ten pages of fifty references,
and hydrates up to four resources concurrently. A thirty-second run budget and
the server's polling interval prevent an unbounded foreground operation. UI and
worker invocations share one execution owner. Parent cancellation propagates;
late success or failure cannot advance a replacement company's checkpoint.

Bootstrap stays invisible until incremental changes have caught up and the
server reports no pending publication. Documents and their encrypted checkpoint
commit atomically. Canonical versions may be newer than feed references; older
results cannot overwrite them. Tombstones dominate an equal version. A failed
hydration retains the previous cursor. A cursor reset may restart once per run.

## Local access and storage

Messages and checkpoints use AES-256-GCM. Associated data binds the session,
account, API audience, company, generation, resource/version, and envelope purpose.
Database indexes retain resource IDs and ordering metadata without message text
or server cursors. Android Keystore, Apple Keychain, and the configured desktop
credential manager protect the separate content-key record.

A caught-up snapshot can be displayed for at most one hour, bounded by the
session-family and selected-workspace expiry. Partial progress does not renew that interval. Observation
hides the snapshot when its lease expires, without waiting for another database
write. Company/account/permission replacement stops the prior observer. Detected
server revocation clears the affected replica. Logout clears derived account
data; this is not a secure-erasure or offline-revocation guarantee. Lease timing
uses the device clock and is not tamper-resistant device attestation.

The [selected workspace](workspace.md) and its bounded admission are encrypted and
restored after process termination. Renewing an unchanged workspace does not reset
the inbox generation or cursor. Browser keys are memory-only and do not survive page closure.
The encryption adapter fails closed for a missing or mismatched key. It never
silently generates a key for existing encrypted content.

## Durable actions

Read and acknowledgement actions persist before transport. Their immutable UUID
and observed version survive retries, process termination and a new session for
the same account. See [command admission, encryption and recovery](inbox-commands.md)
for ownership, finite delivery, conflict review and receipt retirement.

[Company policy](company-policy.md) is checked before feed synchronization and
each delivery attempt. Disabled modules, maintenance, obsolete builds and failed
policy reads pause delivery without replacing the original command or consuming
an attempt during preflight. Re-enabling the module preserves its existing cache
and cursor, subject to ordinary scope and admission checks.

## Interface and validation

The list displays thirty messages per page, with stable item keys and explicit
older/latest navigation. Titles, summaries, and dates share the available width;
rows retain a single accessible action. Detail text is selectable plain text.
Short page transitions follow the existing reduced-motion preference. Initial
loading and refresh are distinct; an offline failure keeps admitted cached content.

Workspace checks and the Android dev build passed.
The real SQLite/encryption tests cover publication, restart, scope/key mismatch,
and stale writes. Domain tests cover cancellation, late results, cursor recovery,
expiry, polling, tombstones, command ownership, original retries, finite attempts,
and safe receipt retirement on JVM and in the browser. Command storage tests use
real SQLite and AES-GCM, including restart, new-session recovery, missing keys,
tampering and compare-and-set races. Transport tests check the actual request
shape and idempotency header. Compose tests cover list paging, offline detail,
company replacement, withdrawal, large text, acknowledgement cancellation,
response loss followed by sign-in again, conflict review, and leaving a pending
operation without losing its identity.

The platform's separate real API/PostgreSQL/worker test verifies publication,
native recipient isolation, versioned commands, replay, and archival sync.
See [validation](validation.md) for Android device observations and remaining
platform checks. No rendering or memory-performance claim is made by these tests.
