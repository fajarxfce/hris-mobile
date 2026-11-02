# Owned native notification transport

The core notification data module has a separate `ActionNotificationDisplaySource`
for bounded native message cards. It performs raw OS I/O only. Existing generic inbox
notification delivery is unchanged. HRIS has not yet wired this surface into its
admitted publication journal, application lifetime or durable reply flow.

`ActionNotificationPayload` carries an opaque stable card ID, a fresh render ID,
bounded title/body, opaque action envelopes and a silent flag. The application supplies
localized labels. Five transport entry points are Open, Reply, MarkRead, Mute and
Dismiss. These names are untrusted routing hints, never authenticated domain actions.
The current HRIS journal has four authenticated actions; an Open/navigation mapping
must be decided explicitly during integration and must never invoke a send/read command
merely because a notification was tapped.

## Android behavior

The adapter retains only the application Context. Open targets an explicit Activity;
the other actions target an explicit receiver that must be private in the manifest.
PendingIntent identity includes the full namespace/card/render/action URI and component,
so a 32-bit hash collision cannot retarget another action. Only Reply is mutable for
RemoteInput. All other actions are immutable. Reissuing an existing render preserves
its original envelope; no UPDATE_CURRENT or cancellation/recreation rewrites it.

Reply extraction accepts at most16,384 UTF-16 units and bounded ASCII envelopes, checks
the exact URI/component/package, and rejects missing/oversized text and remote input on
another action. This is transport parsing only. The application must authenticate the
envelope against its actual session, validate the target and retain input durably before
sending or acknowledging a callback. Bad-parcel/native exceptions remain raw I/O errors
for the application/repository boundary to handle. No reply text is logged or added to
generic notification history by this adapter.

Notifications use private visibility with a generic public lock-screen version without
actions, previews or an opening intent. Generated replies/contextual actions are off.
Channel permission, a disabled channel, capacity and Binder submission have distinct
outcomes. The local capacity precheck includes all of the package's cards at Android's
50-card ceiling and never evicts another card; concurrent external OS changes can still
reject a submission. Submitted does not mean displayed, delivered or read.

Replaying the same currently observed render sets OnlyAlertOnce. A new render can
alert according to the caller's silent flag. After uncertain process recovery the
caller must choose silent replay explicitly; an absent OS record is not proof an alert
never happened. No distributed exactly-once alert guarantee is claimed.

Active reads return opaque references only. Exact removal compares the OS render before
cancelling its stable tag. Namespace cleanup also removes malformed old records without
old-account decryption, and preserves unrelated application notifications. The caller
must serialize submission/removal/cleanup and cancel and join obsolete work. A read of
OS metadata and later cancellation cannot be atomic against another independent writer.

## Verification and integration boundary

The separately packaged emulator fixture uses actual NotificationManager, PendingIntent
and RemoteInput with fictional strings and no HRIS account/provider. It covers denied
permission, private/public versions, stable replay, new-render alerts, stale cancellation,
real reply extraction, immutable-action injection rejection, hash collisions, preserved
same-render envelopes, malformed input, namespace cleanup and a disabled channel.
The driver requires an explicit emulator serial plus qemu/boot checks before installation;
it clears only the separately packaged fixture's data, and never targets the user phone.

`tool/check_native_notifications.py` also runs before the existing five SDK/WorkManager
interop cases in hosted native HRIS CI. Raw notification IPC proof does not replace
those tests, or prove notification-specific retained-input process death. See
[recorded evidence](measurements/native-notification-actions-2026-11-02.json).

Next, connect the HRIS journal and an application-owned serialized lease with parent,
company, source/settings/read and foreground-thread invalidation. Add bounded private
receiver completion, explicit review after failed retention/old-family input, routing,
and notification-specific process-death proof. Implement the iOS host surface separately
and verify full Xcode. Chat push remains off and the user's installed release19 is
unchanged. Port these generic primitives to Starter after integrated Mobile proof;
HRIS admission and cryptographic policy stay in Mobile.
