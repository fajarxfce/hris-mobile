# Admit and publish an original organization runtime

`AdoptCollaborationSpaceRuntime` owns one finite attempt to activate the original SDK
under a current organization. It coordinates repositories and pure policies; it does
not call another use case, select an HR company, enroll a device or replace keys.
Presentation receives only owner/device/operation readiness metadata. The application
retains the runtime and its cleanup responsibility.

The attempt reads the current registered original device, current confirmed bootstrap,
and immutable original handover. An archived operation, uncertain bootstrap or another
native parent cannot authorize installation. Current device and gateway metadata must
match the retained provider/device and confirmed operation. The operation's original
response remains immutable while the SDK keeps refreshed credentials separately.

The server increments gateway versions on normal token refresh. Current metadata may
therefore have a newer version than the original bootstrap; its gateway identifier,
client build and hard deadline must still match. The SDK's original access expiry is
not used to hide local facts. Remote hard expiry is checked using its server-observed
interval charged against local elapsed time, without comparing server and phone clocks.
The whole invocation also has a finite local wall/monotonic and native-parent deadline.

The runtime registry separately records the installed bootstrap's gateway identifier
and version. Under its lock, a higher bootstrap generation for the same gateway may drain that
physical slot. Duplicate exact activation cannot restart a live or still-constructing
reservation; exact replay resumes only after previous resources release. A stale/conflicting operation cannot retire
a newer live resource or one whose native constructor has not returned yet. Normal SDK
token rotation does not change this immutable bootstrap identity.

After acquisition, the use case owns a late returned lease before checking cancellation.
It rereads current registration, setup, device and admission before synchronous
publication. Unaccepted leases are closed, including cancellation, expiry, changed
parents, replaced setup, credential failures and publication failures. A confirmed
organization denial or device revocation stops that exact logical owner; cleanup
failure cannot replace the denial or reopen its projection. Failed private acquisition
checks local admission and returns without another remote status request.

[Validation](measurements/collaboration-space-runtime-admission-2026-11-07.json)
retains the two original failing registry regressions, corrected owning-flow tests,
actual DI verification and source-bound final checks. Original SDK key/namespace proof
is recorded separately in [runtime adoption](collaboration-space-runtime-adoption.md).
No product/root caller, group synchronization or actual old pending-message delivery
is added here. Those consumers must keep owned offline history independent of this
live activation flow and explicitly own ongoing revocation and hard-parent recovery.
