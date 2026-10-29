# Firebase Authentication

The backend [native exchange](https://github.com/fajarxfce/hris-platform/blob/8305f435/docs/firebase-auth.md)
accepts Firebase email/password and Google identities from an approved project.
It verifies the ID token, resolves an explicit HRIS issuer/UID binding and issues
HRIS native credentials. Email matching and provider claims grant no HRIS access.
The existing HRIS MFA flow applies to the resulting session.

## Client session exchange

`SignInWithFirebase` coordinates installed provider capabilities, backend
configuration, interactive authentication and HRIS session publication. The local
and remote project IDs must match, and both must support the requested method,
before provider authentication begins. Input, operation IDs, device names and
opaque proof sizes are bounded. Provider proof is never decoded into an HRIS user.

One coordinator instance serializes interactive attempts with a 120-second total
deadline, including its queue and network work. It checks session ownership after
configuration and provider acquisition, then uses atomic compare-and-set to publish
the HRIS session. Cancellation and late results cannot replace another login.
The native audience and access/family expiry must match the selected HRIS server.
Only HRIS credentials enter the existing session store. Passwords and Firebase
proof objects are redacted, and neither is persisted by this flow.

The caller supplies a stable operation UUID for the original login intent. An
explicit retry can acquire fresh Firebase proof and recover the original HRIS
issuance. The coordinator never retries automatically. Input changes require a
new intent; provider/backend failure leaves the local session unchanged.

The data module binds a separate public-client API/datasource/repository for
`auth/native/firebase` and `auth/native/firebase/login`. Configuration responses
are limited to 4 KiB and credential responses to 16 KiB. Future provider IDs stay
unavailable until the client supports them. Shared API handling owns bounded
response reading, cleanup, cancellation and sanitized HRIS failure mapping.

## Provider and login ownership

Android reads the installed Google Services resources and the current APK signing
certificate. Email/password uses Firebase's fixed HTTPS REST endpoint. Google uses
Credential Manager with a fresh nonce and the configured public web client ID;
the callback must match both before its Google ID token reaches Firebase's IdP
endpoint. The Activity owns attachment, each interaction owns its cancellation
signal, and the application container releases the source on shutdown. Cancellation,
disposal and late callbacks cannot reopen an interaction.

A separate, lazily acquired HTTP client sends neither HRIS authorization/cookies nor
internal tracing context to Google. Responses and provider error codes are bounded;
raw provider errors are not shown or logged. The flow retains no Firebase SDK
session or refresh credential. Google interaction closes before any Google code or Firebase HTTP
exchange begins. Provider account-link confirmation is rejected rather than silently
linking accounts.

The login page discovers installed/backend capabilities before enabling a method.
Matching configuration enables Firebase email/password and Google; explicit disabled
configuration retains native HRIS password login. Failed discovery, mismatched
projects or no shared provider require an explicit retry and never silently switch
the password authority. Submission owns cancellation through completion, keeps its
operation ID on retry, and clears the password on successful HRIS publication.

The real application container owns one sign-in coordinator. iOS now loads public
configuration from the selected application's bundled `GoogleService-Info.plist`,
checks its `BUNDLE_ID` against the running bundle and rejects malformed, missing
required fields or oversized configuration. An absent file reports unavailable;
invalid installed configuration never silently selects another password authority.
The existing bounded HTTPS adapter sends `X-Ios-Bundle-Identifier` for Apple's
application restriction and does not send Android certificate metadata or HRIS
credentials. Provider acquisition still uses the existing cancellation and session
publication boundaries, with no Firebase Auth SDK session or retained refresh token.

iOS Google uses an owned `ASWebAuthenticationSession` with S256 PKCE and independent
cryptographic nonce/state values. It uses the plist's iOS `CLIENT_ID`, validates its
`REVERSED_CLIENT_ID`, and accepts only the exact correlated custom-scheme callback.
Duplicate code/state/error, wrong redirects and malformed callbacks are rejected.
The UI session and window anchor are released before the bounded Google code
exchange starts. Both audience and nonce must match before sending Google's ID
token to Firebase; Firebase verifies the provider signature. Cancellation and late
callbacks cannot start a replacement exchange. The Google token endpoint receives
only the public client ID, authorization code, verifier, redirect and grant type;
no client secret or HRIS credential is sent, and no Google refresh/access token is
retained. No Google/Firebase Auth SDK session is created. Desktop/browser hosts
retain unavailable sources.

## Delivery status

Project `cmp-fajar` has local ignored Android configuration for all three HRIS
flavors. Email/password and Google are enabled, public Auth configuration responds
200, and Google Services processing accepts the public web client ID. Real Firebase
email/password through Android, native HRIS exchange and application MFA passed on
the dedicated emulator. The current dev signing certificate is now registered.
The user tried Google on the installed phone: Firebase confirmed a verified Google
identity, which was explicitly bound in HRIS after retiring the owned fictional
password binding. The user retried and confirmed company/Home; the authorized
server session directory independently returned a new native session. Actual
Google → Firebase → HRIS acceptance is complete on the installed Android phone. One active Firebase UID per
HRIS account/issuer is preserved; enabling both Firebase methods does not authorize
two separate UIDs to be linked implicitly.

Firebase Management supplied matching ignored iOS configurations for dev/staging/prod
in `apps/ios/Configuration/<environment>/GoogleService-Info.plist`. This closes the
missing public configuration prerequisite. Google accepted the dev iOS authorization request and redirected it to its HTTPS
sign-in page without a client/redirect configuration error; no user authenticated
during that check. The native adapter tests and complete Xcode app passed in full CI `40858176601`,
with 97 simulator cases. The generic Google port `e0c6071` independently passed
all three Starter CI jobs in `40864341588`, including 77 native cases and Xcode. APNs and actual
iOS provider/device acceptance remain separate; the [device procedure](ios-acceptance.md)
records remaining setup and observations. See [validation](validation.md)
for executed checks and the [handoff](agent-handoff.md) for current work.

This exchange follows HRIS native-session policy and belongs to HRIS Mobile.
Reusable provider and resource-ownership improvements return to the starter after
they are implemented and validated in this application.
