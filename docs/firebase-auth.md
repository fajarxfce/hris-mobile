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

## Delivery status

The core coordinator and native exchange adapter are implemented. Provider I/O
and login UI wiring remain active work; the existing login page does not yet call
this coordinator. Its provider contract requires adapters to release interactions
on cancellation and return proof without retaining a Firebase session. The final
host composition must own one coordinator instance and its provider resources.

Project `cmp-fajar` has local ignored Android configuration for all three HRIS
flavors. Email/password and Google are enabled, public Auth configuration responds
200, and Google Services processing accepts the public web client ID. Real Google
signing-certificate registration, provider login and device acceptance remain
separate checks. iOS provider configuration is still unavailable. See
[validation](validation.md) for executed checks.

This exchange follows HRIS native-session policy and belongs to HRIS Mobile.
Reusable provider and resource-ownership improvements return to the starter after
they are implemented and validated in this application.
