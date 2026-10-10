# SSO

The starter supports OIDC Authorization Code with PKCE/S256. Android uses AppAuth
and an external browser; iOS uses ASWebAuthenticationSession. Desktop uses the
system browser with an IPv4 loopback callback. Web uses a same-origin popup callback.
The app sends the authorization proof to its backend, which verifies identity and
returns the application's existing session response. The app never treats a decoded
client-side JWT as verified identity.

Default demo mode offers **Demo organization**. Its provider and code exchange are
in-process fixtures; no external identity provider is contacted. It exercises the
same use case, repository and session publication flow.

## Configure a provider

Copy `config/oidc/dev.json.example` to `config/oidc/dev.json`, replace its values, and
register the matching public clients with your provider. Configuration JSON files
are ignored; never add a client secret. The build rejects unknown configuration
fields and callbacks that do not match the runner. Add `staging.json`/`prod.json`
with separate registrations for those environments.

| Platform | Dev callback |
|---|---|
| Android / iOS | `fluentstarter-dev://oauth/callback` |
| Desktop | `http://127.0.0.1:48085/oauth/callback` |
| Web development | `http://localhost:8080/oauth/callback` |

Mobile staging uses `fluentstarter-staging`, and production uses `fluentstarter`.
Desktop may use a different registered port. Web must use the deployed application's
exact origin and `/oauth/callback`; HTTPS is required outside dev localhost.

- **Entra ID:** use a tenant-specific issuer such as
  `https://login.microsoftonline.com/TENANT_ID/v2.0`. Configure native/public clients
  under Mobile and desktop applications with the exact callback. This integration
  uses generic OIDC/AppAuth, not the MSAL broker-specific redirect format.
- **Okta:** create a Native Application for mobile/desktop and an appropriate public
  Web client. Use your authorization server's issuer, enable Authorization Code and
  PKCE, and register exact sign-in redirect URIs.
- **Keycloak:** create public OpenID Connect clients with Standard Flow enabled and
  Client Authentication disabled. Use `https://HOST/realms/REALM` as issuer and exact
  valid redirect URIs. Configure Web Origins for the browser client.

Use `openid profile email` scopes. The app requests `prompt=login` and `max_age=0`
so account sign-in can also serve as explicit reauthentication after an app-lock
reset. Provider discovery must allow browser CORS for Web.

Run against your backend:

```sh
./gradlew :apps:android:installDevDebug \
  -Pbackend=remote -PapiBaseUrl=https://api.example.com/
```

`backend=demo` intentionally keeps the in-process provider even if external
configuration files exist. Public client IDs are not secrets, but provider client
secrets, signing credentials and service-account keys must remain server-side.

## Backend contract

`POST /auth/oidc` receives:

```json
{
  "providerId": "organization",
  "platform": "android",
  "code": "<single-use authorization code>",
  "codeVerifier": "<PKCE verifier>",
  "nonce": "<requested nonce>",
  "redirectUri": "fluentstarter-dev://oauth/callback"
}
```

Select issuer, client ID and token endpoint from server-owned configuration keyed
by provider/platform/environment. Validate the submitted redirect URI against that
configuration. Exchange the code once with the verifier; validate the ID token's
signature/JWKS, issuer, audience/authorized party, expiry and nonce. Enforce the
provider's reauthentication/authentication-time requirements. Do not accept user IDs,
roles or decoded claims supplied by the client as proof of identity.

Return the same `{ user, tokens }` response as password sign-in; see
[sessions and HTTP](runtime.md). Account permissions still come from `GET me/access`.
Keep provider tokens out of the client session response unless a separate feature
requires and explicitly secures them. Logout clears the app session; it does not
perform global provider logout or clear browser cookies.

## Browser and resource ownership

The repository owns one browser resource with Kotlin `use`, including discovery,
authorization, mapping, and cleanup. It performs acquisition and cleanup on Main
and waits asynchronously for SDK/network responses. Resources never cross a
cancellable dispatcher handoff without an owner. An expired/cancelled operation
closes its resource, and a late response cannot publish a different account session.

Android registers a launcher per attempt and disposes it with the Custom Tabs
service; destroying its host cancels that attempt. Retry after Activity recreation.
iOS retains an active presentation anchor only for the operation and cancels the
native session on disposal. Desktop binds only `127.0.0.1`; its callback server and
executor stop when the operation ends. The external desktop browser tab may remain
open for the user to close.

Web reserves the popup before discovery, validates callback origin and window,
and removes its message listener, close monitor, and popup on every exit. Allow
popups for the app origin. Serve `/oauth/callback` through the same Web entry point;
that route forwards the callback and clears its URL without initializing another
app/container. Avoid `Cross-Origin-Opener-Policy: same-origin` for a popup deployment;
use a policy that preserves the opener, such as `same-origin-allow-popups`, and
check compatibility with any cross-origin isolation requirements of your Web host.

The interactive flow expires after two minutes. Coroutine cancellation is propagated;
user dismissal is a distinct cancelled result and does not show a login error.
External provider behavior and backend token verification require integration testing
with the registered clients; the demo does not establish those guarantees.
