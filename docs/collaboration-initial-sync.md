# Initial synchronization and classified acquisition failures

An idle Matrix room can legitimately hold a long-poll request for its requested
timeout. The Android adapter previously requested a 10-second wait even on its
first synchronization, while device readiness allowed 10 seconds for that entire
operation. A response just after the server's wait could therefore miss readiness
without any connectivity or credential failure.

Each sync connection now requests its first delta immediately (`timeout=0`), then
uses the existing 10-second long poll after receiving that response. An interrupted
initial request remains immediate on the existing bounded transport retry. The SDK
continues to own its persisted cursor; `fullState` remains false. No new bootstrap,
key generation, message replay, readiness deadline or polling loop is introduced.

The actual Android adapter is tested against an idle fake server that responds
after the requested timeout. The same fixture verifies interruption before the
first response, shared readiness waiters, cancellation, draining native I/O and
credential rejection while a response is pending.

## Preserve an already classified rejection

Crashlytics access recovered a build79 stack trace at `readRuntimeSession`.
The credential store had already returned an authentication failure, but the
runtime repository threw a new `IllegalStateException` before returning it.
`safeStorageCall` consequently reported a misleading storage error.

Runtime selection and initialization now pass their classified failures through
the acquisition boundary. A rejected acquisition can return no native resource;
that absence cannot become a successful empty result or reach installation mapping.
Mapping runs only after successful acquisition and remains inside `safeStorageCall`.
Cancellation and failed mapping release any acquired resource. Real storage and
mapping exceptions still retain diagnostics; no general error suppression is used.

The regression suite first reproduced the idle timeout, false storage diagnostic
and empty-success result on the previous source. A genuine storage-exception control
passed in that same run. Native lifecycle and refreshed-token continuity still need
their own emulator acceptance; unit success alone does not establish delivery latency
or resolve the separate older-history behavior.
