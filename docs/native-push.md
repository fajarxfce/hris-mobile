# Native push registration

The HRIS identity core implements the native family's push-registration contract.
It is an integration foundation; the Settings entry, foreground coordinator and
authorized Inbox hint delivery are still being connected. The inherited starter
notification demo is not an HRIS registration screen.

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
