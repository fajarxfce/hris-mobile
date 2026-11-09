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
parent observer through rotation, logout and application shutdown. All 74 identity tests and 21 application lifetime/binding cases pass. The final
focused run repeats 21 application cases and passes format, architecture and detekt;
identity inputs remain unchanged from their passing run. Earlier compile and method
length failures remain in [the evidence](measurements/collaboration-parent-session-renewal-2026-11-09.json).
A regression also exposes repeated immediate refresh when a returned token has an
already-due deadline: the old flow makes three bounded attempts instead of one.
Maintenance now consumes deadlines through the completed attempt's refresh margin.
It retains the returned credentials and waits for a useful advancing deadline;
an external successful acquisition can resume normal maintenance. Both short and
already-expired responses are covered. The 74-case identity/quality recheck passes.
The earlier full run was deliberately stopped with 5,868 passing tests and is not
accepted as complete. Full validation must run again on this correction. Native83 must retain the four original messages from complete82
and observe both native and Matrix renewal before release acceptance.
