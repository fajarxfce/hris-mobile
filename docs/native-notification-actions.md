# Owned native notification transport

The core notification data module has a separate `ActionNotificationDisplaySource`
for bounded native message cards. It performs raw OS I/O only. Existing generic inbox
notification delivery is unchanged. HRIS has not yet wired this surface into its
admitted publication journal, application lifetime or durable reply flow.

`ActionNotificationPayload` carries an opaque stable card ID, a fresh render ID,
bounded title/body, opaque action envelopes and a silent flag. The application supplies
localized labels. Five transport entry points are Open, Reply, MarkRead, Mute and
Dismiss. These names are untrusted routing hints, never authenticated domain actions.
The HRIS journal has four authenticated actions. Its application publisher maps Open to
the authenticated Reply target as a navigation-only alias. Actual host routing remains
pending and must never invoke a send/read command merely because a card was tapped.

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

Active reads return opaque references only. Before submission, an AtomicFile under
Android no-backup storage records the latest stable card/render ID. The canonical
inventory holds at most64 IDs and32KiB, contains no preview, token, reply or account
identifier, and is synced and read back before notification IPC. Capacity never evicts
an existing entry. All file I/O runs on the I/O dispatcher.

Under the required serialized sole writer, the persisted latest render fences an old
removal even if the OS card is absent. Cancelling that latest render also cancels an
older still-visible predecessor. Without an inventory entry, removal compares the OS
render. An absent card still receives cancellation. Namespace cleanup unions persisted
IDs and currently active tags, including malformed old cards, without old-account
decryption; unrelated application notifications remain untouched. Only completed
cancellation clears the inventory. Corruption allows best-effort visible cleanup but
throws and preserves the bad file, so the application owner cannot reopen its gate.

The caller must serialize submission/removal/cleanup and cancel and join obsolete work.
No separate writer may bypass this inventory. OS cancellation and local persistence are
not one transaction; cancellation results do not prove a prior alert never happened.

## Verification and integration boundary

The separately packaged emulator fixture uses actual NotificationManager, PendingIntent
and RemoteInput with fictional strings and no HRIS account/provider. It covers denied
permission, private/public versions, stable replay, new-render alerts, stale cancellation,
real reply extraction, immutable-action injection rejection, hash collisions, preserved
same-render envelopes, malformed input, namespace cleanup and a disabled channel.
The subsequent pending-inventory probe adds real-file adapter reopen, absent-card stale
removal, cancellation of an older visible predecessor, capacity and corruption checks.
Absence/predecessor cases use explicit fixture OS calls; capacity uses a synthetic
64-reference file. These are not OS queue-stall, killed-process or power-cut injections.
The driver requires an explicit emulator serial, dedicated AVD, qemu/boot/API checks,
verified debug proof APK identity and an absent proof package before installation. It
never replaces existing data. It uninstalls its owned fixture and verifies package
absence in finally, including after uncertain installation; force-stop failure still
attempts uninstall. It never targets the user phone.

`tool/check_native_notifications.py` also runs before the existing five SDK/WorkManager
interop cases in hosted native HRIS CI. Raw notification IPC proof does not replace
those tests, or prove notification-specific retained-input process death. See
[recorded evidence](measurements/native-notification-actions-2026-11-02.json).
The [inventory increment](measurements/native-notification-pending-2026-11-03.json)
records16 native behavior groups across two scenarios, including the five new groups.
The [sequential cleanup repair](measurements/native-notification-ci-cleanup-2026-11-03.json)
records eight driver tests and a subsequent real Platform-driver ownership preflight.
This corrects the earlier raw driver's pm-clear-only cleanup, which left a package that
the following SDK fixture correctly refused to replace. Full hosted SDK/WorkManager
acceptance remains separately tracked.

The [application publisher](collaboration-notification-publisher.md) now coordinates the
journal and a serialized lease, tested with actual encrypted storage and a fake OS.
Next, bind the host with parent, company, source/settings/read and foreground-thread
invalidation. Add bounded private
receiver completion, explicit review after failed retention/old-family input, routing,
and notification-specific process-death proof. Implement the iOS host surface separately
and verify full Xcode. Chat push remains off and the user's installed release19 is
unchanged. Port these generic primitives to Starter after integrated Mobile proof;
HRIS admission and cryptographic policy stay in Mobile.
