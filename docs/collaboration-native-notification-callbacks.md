# Native notification callbacks

Android callbacks now use the authenticated application actions and protected reply
capture. [Local evidence](measurements/chat-native-callbacks-2026-11-03.json) covers
the actual Android receiver, Activity PendingIntent, RemoteInput editor, Keystore,
Room and notification shade. The [Android application runtime](collaboration-android-notification-runtime.md)
now owns delivery and guarded navigation is integrated. Chat push remains disabled;
provider send from a reviewed native reply is still separate work.

## Ownership and durable results

Reply callbacks admit at most eight inputs and share an eight-second deadline for
capture and acknowledgment. Input must be durably protected before acknowledgment
starts. Once capture succeeds, a failed acknowledgment cannot report the input as
unsaved. Completion joins child cleanup and releases admission even if cancellation
happens before coroutine entry. Saved does not mean Sent.

Acknowledgment authenticates the sealed Reply target, acquires the sole native
writer, persists Dismissing, removes the exact render and finishes Dismissed. It
creates neither a message command nor a read receipt. A newer notification survives
an old acknowledgment; uncertain removal leaves discoverable dismissal metadata.
See [durable disposition](collaboration-notification-action-disposition.md).

The separate nonreply owner admits eight actions with an eight-second deadline.
It accepts MarkRead, Mute and Dismiss; it rejects Open, Reply and unexpected text.
Completed must match the incoming kind. Obsolete is quiet. Android uses a private,
explicit BroadcastReceiver and an application-context facade; goAsync completion
is guarded exactly once. Native error messages never expose tokens or reply text.
Open retains its Activity PendingIntent and cannot use a broadcast trampoline.

Delivery uses its captured, bound Process. Unbound/closed hosts reject actions.
Process waits boundedly for ready, idle native ownership and retries only BUSY
returned before lease acquisition. It never retries an uncertain business mutation.
After lease release it requests discovery to recover any Dismissing record.

## Android direct-reply lifetime

Android can retain a canceled notification and its inline reply history in SystemUI
after NotificationManager no longer reports it. Plain cancellation and immediate
neutral-update/cancel both failed the actual shade assertion in rejected attempts.

The raw adapter replaces visible owned cards with silent, empty, actionless updates
and a public 600 ms OS expiry. It confirms publication with unique opaque markers,
then600ms of grace before explicit cancellation and observed native absence, as described
in [explicit retirement](native-notification-retirement.md). The OS timeout is a
process-death fallback and does not acknowledge removal. Namespace cleanup batches
all updates in the same bounded window. Expiry owns cleanup if the process dies;
uncertainty retains the inventory. No hidden Android flags or privileged APIs are
used. The expiry exceeds the observed AOSP 500 ms reply-history cooldown, and the
successful API35 proof separately checks actual SystemUI absence.

## Validation and remaining integration

The successful native attempt takes 116.032 seconds: 16 raw notification groups,
three ordinary process kills with four reply callbacks, and all five application
actions. Actual screenshots were inspected before acceptance: the original card,
typed inline reply with keyboard, and the shade after both card and editor vanished.
The fixture retains one protected input, creates no message command and performs
zero HTTP calls. MarkRead alone retains its explicit read intent. The fixture is
uninstalled and its owned emulator stopped afterward.

Final root/Android/lint/Wasm checks pass with 3,928 desktop, 230 Android host, 15
proof and ten build-logic cases, zero failures/errors/skips. Fifteen proof cases are
fresh in that final run; other reports are reused from recorded earlier runs.
Twenty-one Python driver tests pass. The stricter final UI validator also accepts
the actual successful XML and rejects all three captured stale-card failures.
CI now requests the same SystemUI path before its existing five provider scenarios.

This fixture seeds a fictional admitted session, conversation, history and publication
journal. It does not execute product navigation, full publication discovery or a
provider reply send. Full hosted Apple and exact-source native results are recorded
separately. Guarded Activity navigation, cross-company selection, production binding,
failure-state UI recovery and edited saved-reply sending remain required. Voice,
video and meetings remain separate open PRD groups.
