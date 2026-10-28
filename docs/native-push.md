# Native push registration

Account → Settings provides **Inbox notifications** on Android/iOS. Enabling the
switch explicitly requests OS access and registers the current HRIS native family;
disabling it retains the disable/retirement until recovery completes. Status reads
owned local metadata, so a failed network request does not erase the preference,
registration expiry or pending outcome. Retry never requests permission.

The application host owns one coordinator shared with Settings. Foreground entry
and session replacement reconcile registration; current SDK token changes trigger
renewal. Stop/disposal cancels its session/status/token observations and active
work. Each mutation joins the old SDK observer before retirement. Replacement
accounts clear their predecessor's status and reject late results. Logout attempts
retirement through the session transition; interrupted/failed cleanup resumes on
the next foreground entry. There is no automatic failed-request retry loop.

Authorized Inbox hint processing is wired into the native receivers and shared
worker. Background registration renewal remains integration work. The inherited
starter notification demo is not mounted by HRIS.

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
credentials remain external acceptance dependencies. Real token issuance, delivery,
foreground/background provider behavior and Firebase login are not yet claimed.
The public Firebase Auth configuration request returned `CONFIGURATION_NOT_FOUND`;
Authentication must be initialized and its intended sign-in provider enabled in
Firebase Console before real login acceptance. No client key is recorded here.
