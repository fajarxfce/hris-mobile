# Native parent renewal for a live chat connection

Native82 kept its original encrypted data and passed settled Home/room traffic,
but its third Matrix refresh returned 401. The request still matched both the
previously returned refresh token and the current database head. The native HRIS
session rotated about 91 milliseconds after that rejection. The server requires
fresh native parent access independently of the Matrix credential deadline; the
original probe did not retain the response's HRIS reason, so that exact rejection
code remains unconfirmed.

The application previously retained the chat client while relying on unrelated
HRIS requests to refresh its parent. That allows an idle Matrix connection to
outlive the parent's ten-minute access token. The gateway still enforces account,
company, device, MFA and session admission; those rules have not been changed.

`AcquireSessionTokens.maintain(sessionId)` observes credential revisions and waits
until the existing thirty-second refresh margin. Its collector uses the same
serialized, durable acquisition as HTTP requests. Recording a pending refresh
operation does not replace the observation, and refresh runs outside the cancellable
deadline producer so its own state writes cannot cancel the exchange. A failed
revision produces one result; ordinary request acquisition or a new owner can retry
the original durable operation. It does not poll an endpoint or add a retry timer.

Application composition owns this observation for the current native parent,
independently of chat routes. Token rotation retains that owner. Logout, parent
replacement and application disposal cancel its collection. A foreign audience or
expired parent cannot start maintenance. The core identity use case owns renewal
policy; the application only binds the lifetime. No SDK, inspector or ViewModel
owns token policy.

Ten domain regressions cover deadlines, successive and competing refreshes,
self-publication, failure without retry loops, external rotation, replacement,
disposal and recovery of the original operation. Two application tests cover one
parent observer through rotation, logout and application shutdown.

A regression exposes repeated immediate refresh when a returned token has an
already-due deadline: the old flow makes three bounded attempts instead of one.
Maintenance now consumes deadlines through the completed attempt's refresh margin.
It retains returned credentials and waits for an advancing deadline; an external
successful acquisition can resume normal maintenance. Short and already-expired
responses are covered.

Final full validation passes on c0b645d0: 5,837 fresh tests (5,082 desktop,
595 Android host, 160 actual browser), with no failures, errors or skips. The 74
identity desktop cases remain current from the focused run, and all ten maintenance
cases also pass in the browser. Root check, release/proof compilation, lint,
shared Wasm/iOS Arm64 source compilation, format, architecture and detekt pass.
The earlier compile failures and deliberately interrupted full run remain in
[the evidence](measurements/collaboration-parent-session-renewal-2026-11-09.json).

Native83 preserves all four original messages and its encrypted identity across
upgrade, route reentry, background and process restart. A fifth new message reaches
the server in 2,895.244 ms while the receiver remains stopped (one sample, not p95).
Native parent renewal occurs 29.064 seconds before its previous access expiry, and
all three successive Matrix renewals return 200 with exact credential continuity.

Strict quietness still fails one room interval: WorkManager starts one periodic
recovery and one completed continuation, producing metadata requests in one ten-second
bin. The other three idle intervals pass, with no bootstrap across the run.
The original failed check remains recorded; startup metadata and the continuation's
exact cause are still under investigation. No phone installation or SDK publication.
Source compilation does not establish Apple runtime acceptance.

The periodic-work review verifies the existing fifteen-minute WorkManager schedule,
one periodic SUCCESS followed by one collapsed continuation SUCCESS in 3.095 seconds,
and two successful Sync traces carrying 22 HTTP reads each. The 42 collaboration
metadata reads occur in their same ten-second packet bin. Three other idle intervals
have no unexpected setup. Removing scheduling evidence, adding a rejection, extra
metadata in another interval, a bootstrap or a failed renewal all reject the review;
the original failed native82 run also remains rejected. Packet bins alone do not
attribute individual requests; the independent WorkManager and trace records
corroborate this bounded sequence.

Build83 is accepted as a progress update for preserved data and token renewal.
The original strict zero-metadata failure remains intact. Startup request volume,
send latency p95, Apple runtime and broader communications delivery remain open.
