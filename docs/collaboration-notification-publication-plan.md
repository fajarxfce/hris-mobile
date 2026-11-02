# Native notification publication follow-through

The [raw journal](collaboration-notification-publications.md) and
[admitted issuance](collaboration-notification-issuance.md) now have separate contracts
and recorded validation. [Exact validation and settlement](collaboration-notification-publication-lifecycle.md)
are also implemented. [Raw Android transport](native-notification-actions.md) is
separately exercised with actual notification IPC in the isolated fixture. HRIS has not
wired it into delivery yet. Continue through serialized publication/cleanup, callbacks
and retained-input review; keep chat push off
until that flow is usable.

1. Inspect PrepareCollaborationMessageNotification and current app/native push lifetimes before changing boundaries. Its Ready projection includes access/conversation/room/event/contentHash/settingsVersion/readVersion/preview/sender/body. It is preparation only, not a lasting OS publication grant.
2. Add a bounded account-owned encrypted publication journal under existing collaboration-history credentials (create=false). Keep stable opaque native-card ID per company/conversation and fresh render UUID. Persist exact action envelopes plus immutable projection source stamps BEFORE OS publication; replay that render after uncertainty rather than resealing it. Never put decrypted chat in generic plaintext notification history. Bound cards/bytes and raw ID page discovery; exact CAS and original owner checks throughout.
3. Separate raw encrypted journal IO from admission. Repository implementations must not call other repositories. Domain issuance/revalidation coordinates repos; application coordinator may orchestrate existing projection UC plus new issuance UC (no UC-to-UC calls). Persist rendered payload/tokens only after validating owned facts/current native family/company denial/settings/read revisions. Handle malformed/cross-owner/version/key-loss results without publishing.
4. Journal state needs explicit publication uncertainty and exact-render dismissal/replacement, so an old reply/mute/dismiss action never clears a new card. Suppressed candidate must not cancel a different card in the conversation. Decide exact historical-card replacement rules against acquired event ordering; never treat arbitrary push order as message order.
5. Application native publisher owns serialized finite operations and native-family/company/settings invalidation. Domain post-IO guards alone do not close the race between validation and OS notify; owner lease must prevent/cancel stale publication and join pending adapter work. Android tag/id and iOS request ID use opaque stable card IDs, with exact render metadata validated before cancellation. Uncertain replay uses stable IDs and does not duplicate alerts.
6. Native reply receiver captures parent from session state, never untrusted intent extras. Validate action kind/envelope/size; retain reply text durably before calling a send/ack workflow. Bound callback lifetime and queue depth; preserve text for explicit review after retention errors/old family. No plaintext reply text in logs. Native input failure before retention needs an explicit UI/retry outcome.
7. Android RemoteInput PendingIntent mutability only where required, explicit private receiver/package/request uniqueness, immutable other actions. iOS categories/UNUserNotificationCenter delegate are host-owned, finite and complete exactly once. Generic native primitives can be ported to Starter only after Mobile proof; HRIS policy/storage/config stays Mobile.
8. Add real encrypted storage/browser tests; cancellation/late account/replaced render/revocation/key-loss/uncertain OS result tests; actual app graph and native OS process-death input replay. Reuse existing SDK/WorkManager proof fixtures but add notification-specific scenarios; pre-existing five native cases do not test reply inputs. Do not repeat long local emulator hang attempts; use isolated hosted native proof when needed.
9. Build state-driven explicit review UI for retained inputs (reason, editable text with new operation only on user submission, safe dismiss for exact input). Use UI/UX/Compose skills when implementing components, real Compose semantics/screenshot inspection on dedicated emulator. Never drive user phone UI. Full Apple Xcode required; physical iPhone waived.
10. Chat push remains OFF until publication/callback/review flows usable. Release19 itself only activates internal reply recovery; it is not native notification/call/video/meeting acceptance. All54 PRD groups remain open; continue full communications plan after this step.

## Exact lifecycle boundary

- Revalidation must compare the exact journal snapshot, current native family/company
  admission, source timestamp/hash, settings/read/identity/peer facts and all four
  authenticated actions. Return a point-in-time check, not a lasting OS grant.
- Published is an acknowledgment of native submission. It requires the current family
  and exact active render. Dismissed/Cancelled remove preview/actions by exact CAS and
  return only opaque native target/version details. Contention must never clear a newer
  journal or OS render; native cancellation compares the OS card's render metadata.
- Cleanup differs from content access. The current account may retire its own older
  native-family records without a fresh company content grant, but may not mark them
  Published. Retained company denial must not prevent cleanup of a displayed private
  card. Parent replacement uses namespace cleanup independently of old-account
  decryption; post-I/O guards still withhold late results and preserve committed facts.
- OS submission is external IPC. Serialize submission and cleanup and join invalidation;
  do not claim an atomic transaction across database/session state and the OS service.

## Candidate discovery and foreground scope

Use acquired originals as candidates, with the issuer's explicit timestamp/event-ID
presentation watermark. This is not causal order. Bootstrap and history backfill must
not flood notifications, and suppression of one unrelated candidate must not cancel
another current card. An app-owned route/lifecycle registration should suppress banners
for the current focused conversation. Register and dispose that scope in navigation
composition; do not store UI state in business repositories or depend on a race with
visible-message read receipts.

Android PendingIntent uniqueness must include opaque card/render identity in the
intent data, rather than relying on a 32-bit request-code hash. Only RemoteInput reply
is mutable. Use explicit private receivers and complete bounded callbacks exactly once;
private text must reach durable reply storage before any send/ack workflow. Test real
notification-specific OS death/replay, since existing SDK and WorkManager interop cases
do not cover those inputs. User-phone updates remain notify-then-install-only.

## Integrating the tested Android transport

The core data surface owns raw OS I/O and opaque references only. The new fixture proves
PendingIntent/RemoteInput/NotificationManager behavior without an HRIS account, and is
separate from application admission and durable input recovery. Keep these boundaries
visible in acceptance evidence.

The application owner must close its epoch on startup, parent change or observer failure,
cancel and join pending native work, and clear its namespace before admitting another
parent. Failed cleanup keeps that gate closed until an explicit finite reconciliation.
Register an active request before a second epoch check so invalidation cannot miss work
that starts concurrently. Serialize all submission/removal/cleanup, bound each request,
and track opaque attempted references before Binder submission. Cancellation after
submission needs exact-render compensation or later journal reconciliation, not a claim
of cross-process rollback. Do not retain private previews in a long-lived tracking map.

Company/source/settings/read invalidation is separate from native parent observation.
ApplicationCollaborationLifetime retains the parent under selected-company denial;
ObserveCollaborationList is selected-workspace only. Neither alone monitors active cards
from every company. Current settings/read repositories lack change observation; add a
narrow owned signal and cancellation/denial tests instead of indefinite polling or UI
state in repositories. Foreground thread registration belongs app/navigation lifecycle.

The raw transport has five routing hints, including Open. The HRIS journal currently
has four authenticated actions. Choose and test explicit navigation-only envelope
handling; an outer Open hint must never trigger a send or mark-read command. The Activity
must receive notification content directly, without a background notification trampoline.

Old-family/pre-retention reply failures still need encrypted explicit review. Existing
Replies.submit handles current-family durable input, and does not by itself retain an
old-family reply. An owned journal snapshot may provide the original envelope/parent
for authentication; never trust a parent copied from intent extras. Bound native callback
completion and finish exactly once after joined work. A denied company must not require
returning decrypted preview solely to find an opaque cancellation target.

Startup namespace cleanup does not settle every retained old journal watermark. Add
bounded opaque discovery and exact retirement/reconciliation that cannot revive a
previously dismissed source. Raw active pages are four records and omit inactive
watermarks. Keep acquired priority hints until publication uncertainty is recoverable;
ordinary missed-hint recovery and initial-sync/backfill suppression need explicit rules.
