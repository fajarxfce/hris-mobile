# Background chat identity acquisition

Incoming recovery explicitly reads the enrolled account's SDK identity while it owns the
finite background runtime. It uses the actual account/company/native family and admitted
device, without reading the selected workspace or opening another SDK runtime. Each room
allows at most two cooperative seconds for identity metadata inside its existing budget.
Missing or transient metadata retains a retry outcome while message-history acquisition
can still progress. A confirmed denial or changed owner keeps the turn gated.

ReadCollaborationIdentity accepts either the existing selected scope or a finite background
grant. Selected local reads keep their existing offline behavior. Background acquisition
checks the grant's device, current native parent, wall-clock expiry and monotonic deadline
before and after SDK/storage operations. A late cancelled result cannot enter storage;
a replacement after an atomic write cannot publish that result into the new owner and
does not erase the old account's owned fact. Missing/failed metadata never deletes an
existing identity, and a different Matrix identity still requires explicit migration.

Only a confirmed SDK response can retain a new access denial. Policy failures such as
expired grants or replaced native families cannot become permanent company denials.
Known company denial takes precedence over concurrent expiry. Cancelled delivery of an
SDK denial still attempts bounded non-cancellable gate retention. Identity acquisition
never clears a denied projection or refreshes unrelated local fact timestamps.

The application binding supplies the use case to incoming recovery. Its actual encrypted
SQLite integration tests obtain identity through that path; notification publisher tests
no longer seed identity directly. SDK/HTTP/OS test ports remain fictional. The next step
is candidate handoff after transport close and before durable priority-hint acknowledgment,
through the one application notification process. Native notification callbacks, input
review, foreground focus, permission wakeups and device acceptance remain separate work.
Chat push remains disabled and this change does not install an APK.

[Validation evidence](measurements/chat-incoming-identity-2026-11-03.json) records83 passing
focused cases and full checks with3,723 desktop,230 Android-host,six proof-host and ten
build-logic cases, plus proof assembly/shared Wasm. Three rejected attempts remain recorded.

The [priority handoff](collaboration-priority-notification-delivery.md) now passes retained
candidates after transport close and retains hints until a known publication outcome.
Production native host activation and notification-input acceptance remain pending.
