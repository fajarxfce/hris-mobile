# Recovering authenticated notification input

An employee may type a reply into an existing notification after reauthentication
has replaced the local or native session. The new action envelope preserves its
issuing parent so the typed input can be retained for explicit review under the
same account, without granting a command in the replacement session.

Newly sealed tokens use `v2.` and an authenticated encrypted payload containing the
original parent and the typed action descriptor. AES-GCM associated data binds the
version, configured API audience and account. Recovery selects this associated data
from the actual captured current parent, never from untrusted notification extras.
The decoded payload must match that account and audience. Local/native session IDs
remain authenticated inside the ciphertext and are compared for normal invocation.
Reply text is never part of the action envelope.

The repository exposes two deliberately separate reads. `open` requires the exact
issuing parent and returns an action for subsequent company/history/command checks.
`recover` authenticates issuing metadata for private input retention within the same
account and audience. Recovery itself neither authorizes a send nor opens a route.
An unrelated account or API audience cannot recover the old action. Existing local
v1 envelopes can still be opened only with their exact original parent; they cannot
recover an unknown parent after replacement. HRIS v1 native notification hosts were
never activated, so this is compatibility for retained local envelopes rather than
a deployment migration requirement.

Both versions retain bounded canonical URL-safe Base64 with issuer padding, a
4,096-character token limit and 3,072-byte ciphertext/plaintext limits. Payload
schemas, parent identifiers, action kinds and optional mute-only fields are checked.
All raw acquisition, decryption and mapping stay inside safe storage callbacks.
The existing history key is required; opening or sealing an action never creates a
replacement key. Plaintext and key buffers are cleared by their owners.

`RetainCollaborationNotificationReply` captures the current native parent and guards
it after pending decode and storage. It accepts only a valid authenticated reply
from the same account/audience and stores the authenticated issuing parent with the
typed text. Storage receives the captured current recovery access separately from the
issuing parent: the database checks the active session for atomic writes while the
authenticated original parent remains in encrypted input. The repository rejects
account/audience mismatches before I/O. A late change of the current account/family still withholds success;
committed input remains recoverable. Company send admission remains separate from
private input retention. Input deduplication uses the original logical descriptor
and text, not randomized ciphertext.

The existing application flow first reproduces any matching durable command. If no
command exists, an old-parent input becomes `SessionChanged` review and does not
reach the command path. Review survives process/container recreation and worker
recovery. A callback for an existing exact command can complete input cleanup without
sending again. Normal commands still use strict `open` and their own current
parent/company/source admission checks.

This increment does not bind native receivers or iOS notification delegates, expose
review UI, or retain unauthenticated raw input when no session/key can open it. A
bounded device-encrypted quarantine and authenticated review remain necessary for
those pre-retention cases. Focused-route suppression, permission wakeups and actual
notification-input OS-death acceptance also remain open. Production native chat
notification hosts remain unbound and chat push remains disabled. Release20 on the
user phone predates this increment.

Validation: focused7 passes126 fresh cases/12 suites. Full8 passes3,766 desktop/532
suites,230 Android-host/37,six proof-host/one,ten build-logic/two and41 browser/three,
with zero failures/errors/skips. Root quality, proof assembly and shared Wasm compile
pass. [Evidence](measurements/chat-notification-action-recovery-2026-11-03.json) preserves
six rejected attempts,2,212 fresh full-run cases and19 frozen source hashes. Hosted
Apple543c61b9 predates v2; consolidated full Xcode validation remains separate.
