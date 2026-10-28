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

Authorized Inbox hint delivery and reconnect scheduling remain integration work.
The inherited starter notification demo is not mounted by HRIS.

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
foreground/background hint behavior and Firebase login are not yet claimed.
