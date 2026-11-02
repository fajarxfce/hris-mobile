# Durable chat recovery requests

Recovery uses one application-global `hris.collaboration-recovery` scheduling key.
`StoredCollaborationRecoveryWakeRepository` maps the raw work-wake store into a
pending checkpoint with a fresh canonical UUID. It validates the returned key,
generation and pending flag within the storage failure boundary. New-key capacity
is 32; exhaustion returns `client.collaboration_recovery_capacity`, without a retry.
An acknowledged raw row maps to no pending wake. The record carries no identity,
message, credential, provider data or permission to acquire company content.

`RequestCollaborationRecovery` commits its generation before requesting platform
execution. Enqueue has a 1.5 second timeout. A failed, cancelled or timed-out enqueue
does not acknowledge or remove the retained wake. Cancellation immediately after a
database commit stops subsequent scheduling but leaves recoverable durable state.
Success means execution was requested, not that messages were acquired or delivered.

`ResumeCollaborationRecovery` reads the pending checkpoint and requests execution
without replacing it. It returns false when none exists. Startup, reconnect and
periodic owners must repair write/enqueue gaps; those owners are not wired yet.
Fresh account/login sweeps remain necessary independently of an old global hint.

A worker reads its checkpoint through `ReadCollaborationRecoveryWake` before doing
bounded, independently authorized recovery. `CompleteCollaborationRecoveryWake`
acknowledges only that exact generation. False requires another pending read: a
newer wake or another completion may have won. It does not acknowledge a message,
clear encrypted commands, authorize an account or extend an admission lifetime.
A cancellation after acknowledgment may race the committed change; later reads
observe the durable result. No operation attempts to undo a committed result.

[Validation](measurements/chat-recovery-wake-2026-11-02.json) records ten domain and
six data regressions on desktop/browser, plus assembled application DI and actual
SQLite fencing. The [bounded Android scheduler](bounded-work-scheduling.md) now has separate real
WorkManager proof. Registered all-company workers, FCM receive/decrypt and notifications
remain outstanding. These HRIS operations currently have no application entry-point callers.

The [all-company consumer](collaboration-company-worker.md) now registers this key,
creates periodic hints without recursive scheduling, and consumes the retained
progress. Startup and Android slot opt-in are wired. Its current company callback
covers outgoing delivery; incoming/notification/provider acceptance remains separate.
