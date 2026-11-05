# Durable native organization setup commands

Account/space-owned setup storage retains original native retry/bootstrap requests
and the original registration operation reference. Replaceable local login handles
and selected HR company are excluded from immutable request identity. Retry keeps
platform/build provenance without changing its wire key; bootstrap requires its
retained platform/build to match the request. Current admission, age and SDK private
key ownership are separate from local storage validity.

One active intent per original native parent uses `space-setup/v1/{space}` and
`setup/{nativeParent}` under the active account scope. Request and outcome have
separate authenticated purposes bound to audience/account/space/operation/native
resource/revision. JSON plaintext is limited to 4 KiB, ciphertext to 8 KiB, active
account observation to 128 unique operations/native resources. The shared protected
command key is never generated over retained active, inactive or archived data.
Empty reads allocate no key. All acquisition and mapping use safe storage boundaries.

Pending becomes Unconfirmed before transport; uncertain failure preserves a known
access/MFA denial. Exact command/revision/outcome compare-and-set prevents competing
writers from changing original intent. Retried must name the original device and
provisioning revision + 1. Bootstrapped must match original native binding, selected
provider user/device, gateway version and group token namespace; credentials stay
encrypted and all exposed string representations are redacted. Terminal success
cannot return to pending work. No storage read grants live SDK authority.

After the owning use case has observed a confirmed retry receipt in current native
metadata, exact archival retains both original command and authenticated outcome
as immutable completion evidence. A lost archive response recovers the same result;
same-operation insertion does not recreate pending work, and a different operation
can use the released active slot. Exact find-by-operation can recover the archive.
Malformed evidence, mismatched operation or non-retry terminal state is rejected.
Bootstrap retirement remains the owning durable SDK-adoption flow's responsibility;
this repository cannot archive or delete its received gateway credentials.

Fifteen common storage cases pass on actual SQLite and IndexedDB, covering encrypted
gateway restart/new login, original intent, lost commit responses, contention/CAS,
terminal outcomes, native/account isolation, wrong provider/token/bounds, inactive
archived key loss, immutable archives, retained denial and bounded observation.
Actual AppContainer resolves the owning repository. Focused: 41 cases /
119.196 s. Root :check, Android-host and dev compilation: 2,698
fresh cases / 339.227 s. Browser data/domain: 1,606 fresh cases /
138.797 s. Final gates preserve identical 4,006 build/application
inputs; format, architecture and detekt pass. All gate sessions closed.
[Evidence](measurements/collaboration-space-native-setup-storage-2026-11-05.json).

Next implement finite explicit native setup preparation/replay and retry receipt
reconciliation. Reacquire the original Registered anchor and current identity/status;
check admission and original native hard/wall/monotonic budgets around suspension.
Persist uncertainty before HTTP and gateway credentials before publication. Keep
confirmed denial through failed storage/cancellation. Changed parent/build cannot
rewrite an uncertain bootstrap; explicit metadata reconciliation/recovery is separate.
A missing gateway in metadata cannot disprove late original bootstrap completion,
especially after a build change or the 30 s replay window. Explicit operation recovery
or supersession must preserve the original and durable replacement intent; this
storage slice deliberately cannot retire an uncertain or bootstrapped command.

Retained-store inventory, hard-parent recovery, old-runtime freeze/drain/handover,
original-store adoption, current group runtime/UI and typed push/background replies
remain unfinished. No product route is activated by storage. No new APK/phone, live
deployment, provider/FCM, JNI/private-key proof, Apple runtime or performance acceptance.
All 54 groups stay open; backend 8b40/schema 116 and phone 29/source 084662c5 unchanged.
