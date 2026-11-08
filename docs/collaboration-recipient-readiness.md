# Recipient readiness and retained delivery

Passive phone69 capture matched the failing room to an active peer with no published
chat device. /transport200 did not distinguish that fact; encrypted send403 was
flattened into transient delivery_pending. See the Platform recipient-readiness
investigation and its closed measurement. No phone UI or message was driven.

The additive transport fact recipientReady is mapped without caching in data.
The existing pure domain transport admission checks it before staging or resuming
an original native transaction. False returns a distinct Conflict, not an account
or content denial. The existing foreground/platform worker maps this to Blocked:
there is no timer retry, owned local text remains readable and the original intent,
queue mapping and native store remain intact. Independent conversations continue.
The server continues its independent current recipient check at actual submission.
Readiness is not a reusable grant. An offline peer with a previously confirmed,
otherwise eligible device remains ready; first-time setup is a separate condition.

The focused gate passed75 fresh cases in272.703s with zero failures/errors/skips
and all4,488 app/82 library inputs unchanged. Full gate14084 passed3,987 fresh cases without failures/errors/skips in547.126s;
all app/library source inputs remained unchanged. The eleven Kotlin changes are accepted.
[Attempt evidence](measurements/collaboration-recipient-readiness-2026-11-08.json).
Coverage includes API mapping (including absent
field), no native mutation when unready, retained transaction delivery after a later
ready read, durable restart, another room's progress and the scheduler's existing
blocked/no-timer contract. Deploy the additive server response before the mobile APK.
Backend1aea3228 is deployed and healthy on schema116.1. Phone69 and stopped original
emulator69 remain unchanged. Release70 can now be packaged after source push, and still requires device acceptance.

## Intended first-use experience

The user asked for the appropriate behavior before a recipient has keys. An unready receiver should
leave the sender's original bubble locally retained with concise localized text:
“Menunggu penerima mengaktifkan chat”. The recipient device generates private keys
and publishes only public material during automatic first-login chat setup.
An admitted readiness-change event should wake retained delivery; it does not itself
authorize sending. The current transport is checked again before the same transaction
resumes. Ordinary receiver offline status must not block sending to the server.

The readiness label and cross-device event wake are NOT implemented or accepted by
this first contract change. Existing explicit recovery/startup/ownership/new-work
signals can recheck blocked work. Do not claim immediate automatic recovery after
peer enrollment until the event, lifecycle and loss/replay behavior are tested.
Do not fabricate receiver keys, make private keys on the server, reset Bima, clear
old commands or mark messages sent without a provider event receipt. Preserve the
unsent Draft66 092041 and never replay any older synthetic original.

The existing Matrix sync gateway already retains scoped device_lists.changed/left.
The underlying Rust SDK exposes encryption().devices_stream(), while the current
FFI does not expose that stream. This is the preferred event-source candidate to
evaluate, not a new polling endpoint. It needs an owned cancellable listener, scoped
coalescing, missed-event/startup reconciliation and publication-race tests before
activation. A generic every-sync callback must not recreate the request loop.

A persisted delivery hold must remain separate from the original transaction state:
Queued/Staging/Staged still describe what happened to the original command. The
owning use case records or clears a bounded recipient-setup hold with optimistic
revision checks, repositories persist/map it, and presentation renders its localized
meaning. Clearing the hold cannot create a fresh logical send or discard an uncertain
old transaction. The UI should show one restrained conversation-level explanation
when applicable; do not repeat a technical error under every bubble or add network
status to the app bar. Final placement requires the UI skills and screenshot review.

Foreground device-list updates alone do not wake a terminated sender application.
The background path also needs a scoped, coalesced FCM/APNs readiness hint emitted
from committed key-publication work, with durable/idempotent outbox delivery. The
hint contains no key, message text or sending authority. Startup/current-state
reconciliation covers missed hints; a push permission failure cannot discard the
message. Evaluate this against the existing native notification recovery lane, not
a new always-running socket or tight background polling loop.

Current enrollment is still driven by ChatSetupViewModel; EnsureCollaborationRuntime
only restores an already enrolled device and explicitly does not enroll. Automatic
first-login activation therefore requires a named domain workflow coordinating
registration/provisioning/bootstrap repositories and existing pure policies, with
retained idempotent setup commands. It must not hide setup calls inside the runtime
datasource, call use cases from other use cases, or copy the screen's orchestration
into an app bootstrap helper. Existing-account keys are restored, never regenerated
to make this missing-recipient case disappear.

The boolean is only a current eligibility fact. The existing projection excludes
departed/inactive/revoked recipients as well as absent or unpublished devices. A
future activation-specific label needs a safely scoped reason; it must not mislabel
a departed account as simply waiting for first login. Preserve the distinction when
adding the persisted UX outcome, without trusting a cached fact for actual sending.

The additional180.181s phone69 passive window observed no transport request, so it
cannot establish the response flag or prove that the old mobile retry loop stopped.
