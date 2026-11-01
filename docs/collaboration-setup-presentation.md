# Recoverable Chat setup

The setup page reads the currently owned device and retained setup intent before
choosing an action. It never registers, retries provisioning or bootstraps merely
because the page appeared. Typed actions distinguish activation, waiting for the
worker, provisioning retry, continuation of saved work, revoked access and readiness.
Refresh performs finite reads; there is no automatic provisioning or retry loop.

`ReadCollaborationControl` reads retained setup or per-peer/per-room commands locally.
It binds the result to the selected account/company/native family, checks the shared
admission gate around acquisition and rejects wrong resources or malformed bootstrap
bindings. Age and a later application build do not rewrite or erase the original
operation. An expired live credential can therefore leave a recoverable local intent
visible while the separate live read reports that verification is needed.

`LoadCollaborationSetup` now maintains the same company admission gate as conversation
search and local history. A received company denial is retained even on cancellation;
failed persistence and failed retries cannot reopen content. Successful acquisition
clears only the revision observed before the request. A newer denial or replaced
account prevents publishing the device result. An explicit entry or Refresh can attempt
admitted recovery when the initial local gate is closed.

Continue re-reads the retained command. Pending and uncertain outcomes replay exactly
that operation. Confirmed registration and provisioning receipts reconcile without
resending; they remain retained if the server has not yet exposed the matching result.
A missing command while the UI still says Continue causes a fresh read, not a guessed
replacement. Confirmed bootstrap goes directly to protected SDK installation and
command retirement. The presentation-safe `InstallCollaborationRuntime.activate`
returns owned device identity without exposing the application's parent close handle.

For an active registered device without pending control work, admitted entry may
restore an existing paused runtime through `EnsureCollaborationRuntime`. A missing
local runtime requires explicit activation. Readiness is durable screen state before
Open chat emits its navigation effect. The page never starts timeline sync or message
delivery. Closing the page cancels its requests while preserving uncertain intent;
its local ownership observer remains until ViewModel disposal. Account/company loss
clears hidden setup state. Late successful native acquisition is still closed by its
use case if cancellation or replacement prevents transfer to the application owner.

No gateway token, encrypted store key or raw setup command is placed in UI state.
The page stores only device status, operation kind, failures and the ready device ID.
It uses existing Fluent page/button components with a stationary app bar and scrollable
body, a 600 dp maximum width and reachable actions at 200% Indonesian text.

The current bootstrap contract still pins the original build/platform. Cross-build
uncertain bootstrap, expired gateway issuance, cross-family key continuity and lost-key
recovery need their explicit protocol before being exposed as automatic recovery.
Neither this screen nor Refresh silently deletes a protected store or changes an
uncertain operation. Confirmed non-bootstrap receipts can already reconcile after an
upgrade. Application routes, message-thread composition, delivery scheduling and all
remaining P0–P8 implementation continue; this page alone accepts no complete PRD group.

[Validation and source hashes](measurements/chat-setup-2026-11-01.json) retain all
attempts. Reviewed fictional states:

![Activation at 200% Indonesian text](images/chat-setup/chat-setup-indonesian-large-text.png)

![Ready setup](images/chat-setup/chat-setup-ready.png)
