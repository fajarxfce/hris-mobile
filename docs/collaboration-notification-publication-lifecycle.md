# Exact notification validation and settlement

`ValidateCollaborationNotificationPublication` checks one exact active journal snapshot
under an eight-second deadline. It captures the current native parent, checks retained
company admission, rereads the journal, verifies owned original/settings/read/identity/
peer facts and timestamp, authenticates all four stored actions and checks the source
and exact journal again. Its Unit result is a point-in-time check. It does not show a
notification, renew credentials or grant a lasting OS lease.

Action authentication is an internal implementation shared with issuance's exact
replay path. Neither use case invokes another use case. Shape/encoding checks remain
separate from cryptographic opening, source admission and native publication lifetime.
A changed parent, company denial, old render, changed source/preview/read coverage,
malformed envelope or pending operation cancelled at its deadline withholds validation.

## Submission acknowledgment and cleanup

`FinishCollaborationNotificationPublication` is a singleton with a nonwaiting mutex
and an eight-second budget. Its caller supplies the exact snapshot and Published,
Dismissed or Cancelled outcome. Published records the application's acknowledgment of
native submission; it is not evidence the person saw or read the message. It requires
the current native family and an active record. The native coordinator is responsible
for calling it only after the actual adapter outcome.

Cleanup is separate from company content access. The current account can retire its
own old-family record even after losing company access or reauthenticating locally.
It cannot mark that old-family record Published or mutate another account's record.
Cleanup does not load original messages, company sources or action envelopes. It
retains the source watermark and removes preview/actions with one exact repository CAS.
The result contains only opaque record/card/render IDs, revision and resulting state.

An absent or replaced record returns contention. It is not automatic success. An exact
already-settled record needs no new write; an inactive record cannot be reactivated by
settlement. The repository response must match the requested body, record identity and
an advancing revision. Neither use case retries internally. Cancellation and late
parent replacement can withhold a result after commit; the durable result remains
available for later reconciliation.

## Native integration limits

Native cancellation must compare the OS card's exact render metadata before removing
its stable ID, and all submission/cancellation operations must be serialized by the
application owner. Owner invalidation cancels and joins obsolete work. Namespace
cleanup after account replacement must independently remove obsolete native cards
without decrypting the replacement account's data. A domain check and database CAS
cannot form an atomic transaction with the external OS notification service.

These use cases activate no platform notification or callback. The application still
needs candidate discovery/backfill rules, focused-thread suppression, native adapters,
bounded callback retention, explicit failed-input review and notification-specific
process-death proof. Existing SDK/WorkManager interop scenarios do not test those
notification inputs. Chat push remains off; the authorized phone remains on release19.

## Validation scope

Fourteen common cases cover exact read-only validation, stale/malformed/source changes,
late denial/parent changes, envelope rejection, joined cancellation/deadlines,
idempotent acknowledgment, inactive watermarks, old-family cleanup, another-account
rejection, uncertain writes and malformed repository responses. Four application
cases use real Koin/SQLite/cipher to exercise reopen, denied old-family cleanup,
replacement privacy and cancellation after an actual commit. HTTP/provider facts are
fictional/simulated and fixture credentials remain in memory across reopen. An explicit
Published test transition is not simulated evidence of an OS notification being shown.

[Recorded checks](measurements/chat-notification-lifecycle-2026-11-02.json) include the
existing issuance, raw publication, action-envelope and application-binding regressions,
portable browser tests and full root checks. These HRIS policies stay in Mobile; generic
native primitives may return to Starter after Mobile proves the integrated flow.
