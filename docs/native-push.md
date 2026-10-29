# Native push registration

Account → Settings provides **Inbox notifications** on Android/iOS. Enabling the
switch explicitly requests OS access and registers the current HRIS native family;
disabling it retains the disable/retirement until recovery completes. Status reads
owned local metadata, so a failed network request does not erase the preference,
registration expiry or pending outcome. Retry never requests permission.

The application host owns one coordinator shared with Settings. Foreground entry
and session replacement reconcile registration; explicit Settings actions retain
permission and mutation ownership. Stop/disposal cancels its session/status
observations and active work. Replacement accounts clear their predecessor's
status and reject late results. Logout attempts retirement through the session
transition; interrupted cleanup remains retained.

Android token callbacks request the connected WorkManager task `hris.native-push`.
Periodic work renews expiring registrations and recovers missed callbacks. Apple
callbacks request the same shared task for the foreground host; BGAppRefresh runs
it before the other sync tasks and cancels it when its OS execution expires.
The UI does not maintain an SDK token observer. All SDK acquisition/deletion and
backend mutation go through the same reconciliation mutex, including concurrent
foreground disable and background renewal.

Automatic execution persists its attempt before provider/backend I/O. Uncompleted
attempts back off from 30 seconds to 15 minutes and stop after eight, including
process restart or cancellation. Successful reconciliation clears that budget;
foreground entry or an explicit Settings action permits a new recovery sequence.
Pending status remains visible from local storage. The worker owns a 35-second
execution deadline around the existing 30-second reconciliation. Empty or
unsupported installations never acquire SDK tokens or request OS permission.

Authorized Inbox hint processing is wired into native receivers and the shared
worker. The inherited starter notification demo is not mounted by HRIS.

## Ownership and recovery

One secure installation record stores the explicit preference, stable native
family/account/audience owner, provider token, observed server metadata, frozen
operation and outstanding retirement. Android uses its encrypted credential store;
iOS uses a separate Keychain record. Token values and command bodies are redacted
and never enter the public status model.

`ReconcileNativePush` serializes finite, cancellable reconciliations with a
30-second deadline. Explicit enable requires OS permission; resume never prompts.
A changed owner retires its SDK token before another family can register, retaining
failed cleanup. Previous native families can be revoked only after the same
account authenticates again. The queue is bounded to 32 families and each run
attempts at most four revocations. Desktop/Web report unsupported without SDK I/O.

Each PUT/disable operation is persisted before transport and pins the local
session, payload, observed version and idempotency key. A lost response or process
restart replays that operation before new work. Receipt recovery reads the latest
server metadata, so replay cannot reactivate a later disable. Revoked OS permission
also disables registration. Unchanged tokens are not renewed until the registration
is within one day of expiry. Failed storage, cancellation and replacement sessions
cannot publish an unowned result. Corrupt local records fail without becoming an
empty installation or overwriting a retained operation.

Native family boundaries are retained in milliseconds. Server registration
metadata inside the same final millisecond is accepted and clamped to that local
boundary, without extending its lifetime. A later millisecond is invalid. Cleanup
of a previously owned family treats only the exact `native_session_not_found`
absence result as retired; other errors stay queued, and another account's family
is never revoked by the current account.

## Inbox hints and notification attempts

FCM messages contain only schema/event identifiers and account, company and Inbox
IDs. Android accepts data-only messages; Apple extracts these bounded fields from
APNs metadata. Provider titles, bodies and destinations never select displayed
content or navigation. Receivers validate canonical UUIDs, require the current
account/API audience, and retain the hint in a separate encrypted installation
record before scheduling work. Reception has a three-second local deadline.

The queue retains at most 64 pending hints for one day and at most 512 combined
pending/attempt records. Attempt records retain their own account/company/audience
for seven days, including across account changes. Duplicate reception cannot
extend a hint's lifetime. Corrupt/oversized storage fails without overwriting the
record. These are optional delivery hints; the canonical Inbox is acquired through
normal synchronization even when a hint expires or cannot be retained.

The worker keeps the selected company, requires current HRIS admission and grants
each hint at most three durable network preparations. It synchronizes the Inbox,
then reads the canonical item before display. An unread item must also exist in the
published local replica at the canonical version or newer. Confirmed denials stay
gated through failed cleanup; another admitted synchronization must clear them.
Cancellation, session replacement, changed company, expired preparation, disabled
push, revoked OS permission and already-read items cannot start a new alert.

Before calling the OS, the worker atomically replaces the pending hint with an
attempt record. An uncertain/cancelled OS call is not repeated. This trades a
possible missed optional alert for duplicate suppression; it does not guarantee
that the OS showed an alert. Android uses the full event ID as the notification
tag and alerts once for replacement. The notification says only **HRIS** and an
English/Indonesian prompt to open Inbox. Its internal resource path carries account,
company and item IDs, and the existing HRIS link admission checks it again on tap.
Displaying or opening this link does not send a read/acknowledgement command.

Android WorkManager schedules connected work. Apple foreground work and remote
background fetch share the same serialized worker, with a 25-second fetch owner;
OS throttling, suspension and force-stop can prevent delivery. Platform SDK and
real-device acceptance are recorded separately from fixture tests.

## Provider configuration and acceptance

The supplied Android configuration for Firebase project `cmp-fajar` is installed
locally in `apps/android/google-services.json`, mode 0600 and ignored by Git. All
three HRIS application IDs have real Firebase registrations. Dev assembly and
staging/prod Google Services processing passed. Configuration processing does not
prove provider delivery.

FCM and Firebase Authentication are both requested. Firebase sign-in must exchange
a verified external identity for HRIS authorization; registration tokens are never
account identity. iOS provider configuration, APNs signing and worker FCM service
credentials remain external acceptance dependencies. Actual Firebase SDK token issuance, registration, response-loss recovery,
offline restart and explicit retirement now have
[Android evidence](validation.md#firebase-registration-and-offline-recovery-on-android).
FCM message delivery and foreground/background provider hints remain unaccepted.
Firebase email/password login and the Android system Google sign-in launch/dismiss
flow have [separate device evidence](validation.md#real-firebase-android-acceptance).
The user enabled Email/password and Google. Public Firebase Auth configuration now
returns HTTP 200. The supported Google authorization setup endpoint supplied the
public web OAuth client ID; it is installed locally for the three HRIS Android
flavors. All three Google Services processing tasks passed. The earlier
`CONFIGURATION_NOT_FOUND` result is resolved. Signing-certificate registration and
Google token issuance/exchange still need provider acceptance. No client key is
recorded here. [Android URL acceptance](deep-links.md#android-url-acceptance) verifies
resource navigation and retained read state independently of provider delivery.
