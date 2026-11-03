# Application registration for chat push

Status: local source validation accepted; actual product FCM and matching new-source
Apple/native acceptance remain pending.
The earlier managed pusher adapter and use case had no application caller. A native
FCM token alone could therefore not establish a chat push binding for the installed
Matrix identity. Live chat push remains disabled until product acceptance and rollout.

The application recovery sweep gives each admitted company three finite turns:
incoming messages, outgoing messages, then push registration. Each has its own
15-second budget; registration failure cannot prevent either message turn. All use
the same original background admission, and the existing account sweep's overall
budget and parent replacement cancellation remain in force. Combined outcomes keep
pending work and failures; a successful registration cannot erase another failure.

Registration reads safe native push status for the captured local session. Unsupported,
disabled or denied notifications require no SDK operation. An enabled but unconfirmed
or pending native registration retains a retry. The worker borrows only an already
installed protected identity through the admitted background-runtime use case, then
checks native status again. It neither enables notifications nor enrolls a chat device.
Credential/interruption and local-access observers cancel and join obsolete provider
work. Every acquired reference is released, including late success, timeout and
cancellation; a failed bounded release cannot be acknowledged as Complete.

`ActivateCollaborationPusher` now accepts selected or background operation scope.
Background calls must use the admitted device and at least the observed version.
They do not select or read a UI workspace. Current native parent, finite admission,
company access, device state/platform and retained denial are rechecked around I/O.
The existing endpoint still receives no client push token or provider configuration;
it validates the current native registration and sets the managed opaque Matrix key.

The application lifetime observes native registration metadata under its current
native parent. An enabled confirmed registration requests existing durable recovery
work. Switching company retains the same parent; replacing a parent cancels and
joins the old status observation. Missing/failed/disabled states do not schedule.
Unchanged state does not repeat requests, and a failed or timed-out scheduling request
does not create an observation retry loop. Native metadata contains no token.

Chat setup persists a recovery wake after successful installation or restoration.
Its UI becomes ready independently of the bounded scheduling request; interruption
leaves periodic recovery able to discover the durable hint. Wakes carry neither
message content nor access authority. Provider failures are retried only through
later scheduler/user-triggered work, under fresh admission.

Validation covers background scope/device/version/expiry and native replacement,
permission and registration gating, disable during borrowing, exact resource cleanup,
revocation/cancellation, concurrent entry, finite deadlines, independent message turns,
registration observation replacement and setup wake failure. Full root/Android checks,
matching Apple/Xcode, hosted provider coverage and real product FCM delivery must be
recorded separately. No generic Starter port is needed for HRIS admission policy.

## Local validation — 3 November 2026

[Evidence](measurements/chat-push-activation-2026-11-03.json): full xvfb root check,
shared Wasm compile, Android product/proof assembly and lint PASS495.015s. Source
hashes remain unchanged. Reports including reused cases:4,015 desktop/566 suites,
230 Android host/37,18 proof-host/five,ten build-logic/two; zero failures/errors/skips.
2,421 cases/298 suites freshly execute. Product lint has zero issues; proof retains
47 existing warnings, zero errors. Selected behavior includes11 pusher-domain,
12 setup, eight registration-worker, four wake-observer and nine company-turn cases.

Earlier failures remain recorded: missing permission sources in minimal containers
and an incorrect test expectation for close failure, then a105-line DI function.
Lazy status binding, explicit denied permission in isolated worker fixtures and a
private DI factory resolve these without adding an application fallback permission.

The separate `hris-product-peer` instrumentation scenario compiles and is owned by
the proof package. It uses a fictional encrypted sender and observes a decrypted
reply with a logical ID; the actual product remains responsible for setup, pusher,
FCM reception and native reply. Its execution is still pending in Platform's owned
FCM fixture. Never install the loopback fixture product APK on the user's phone.
