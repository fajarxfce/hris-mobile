# Sessions and HTTP

The default backend is an in-process demo. It supports `demo@example.com` and
`casey@example.com`, both with password `Demo123!`. Tokens are fixtures, not JWTs.
No authentication request leaves the device in demo mode.

## Session ownership

`SignIn`, `SignOut`, `RestoreSession`, and `AcquireSessionTokens` coordinate the
identity and session repository contracts. Datasources do raw I/O. Token refresh
is serialized in the use case; the Ktor plugin only adapts HTTP requests to it.

Android encrypts the session using an Android Keystore key and keeps ciphertext
in the no-backup directory. iOS uses Keychain with
`AfterFirstUnlockThisDeviceOnly`. Desktop uses the OS credential manager through
java-keyring. Missing or locked credential storage produces a storage failure;
it does not silently downgrade to a plaintext file.

For a desktop without a credential manager, explicitly use ephemeral sessions:

```sh
./gradlew -PdesktopPersistence=memory :apps:shared:run
```

Web bearer tokens are memory-only. Persistent browser authentication requires a
backend with an HttpOnly, Secure cookie strategy; localStorage is not used for
credentials. Reloading the Web app drops its previous account cache along with
the ephemeral session. DataStore contains preferences, not tokens.

A new sign-in receives a new session ID. Refresh preserves that ID. The database
owns one active account cache, rejects reads/writes from old sessions, and clears
dashboard content, saved preferences, outbox and inbox when the session changes.
Sign-out discards pending local changes. Preferences such as language and
onboarding completion are device-wide and remain available.

The vault is authoritative. A short, non-cancellable local commit updates cache
ownership, credentials and the in-memory observation. It contains no network
work. These stores do not share a distributed transaction: a vault write failure
can discard derivative cache data; the next read reconciles ownership from the
unchanged vault. Storage corruption is reported rather than silently signing out.

## Remote backend

```sh
./gradlew -Pbackend=remote -PapiBaseUrl=https://api.example.com/ :apps:android:assembleDevDebug
```

`apiBaseUrl.dev`, `apiBaseUrl.staging` and `apiBaseUrl.prod` override `apiBaseUrl`.
Use HTTPS URLs without credentials, query strings or fragments. Public and
authenticated clients are independently named in DI. The authenticated client
only sends tokens to its configured origin and does not follow redirects.

The example contract is:

- `POST auth/login`: `{ "email": "…", "password": "…" }`.
- `POST auth/refresh`: `{ "refreshToken": "…" }`.
- Both return `{ "user": { "id", "name", "email" }, "tokens":
  { "accessToken", "refreshToken", "expiresAtEpochMillis" } }`.
- `GET dashboard?cursor=…` returns counts, activity and an optional `nextCursor`.
- `PUT dashboard/preferences` accepts an activity preference with `Idempotency-Key`.
- Optional validation errors use HTTP 400/422 with
  `{ "errors": { "email": "invalid", "password": "required" } }`.
  Recognized codes are `required`, `invalid`, and `already_exists`; other codes
  become a generic rejection. Server messages are not rendered.

Requests carry their originating session. Concurrent 401 responses share one
refresh; a changed account prevents replay. A request is retried at most once,
only with a repeatable body and an idempotent method or idempotency key. Refresh
revocation clears the original session; temporary network failures retain it.
The backend must implement token expiry, refresh rotation/revocation and request
idempotency. Integrate its actual contract in the API/DTO layer.

### Browser transport failures

Ktor's Wasm Fetch engine can reject with `Error(cause = JsError)` and throw `JsError`
while reading a response stream. The network boundary recognizes these transport wrappers
and maps them to a network failure, retaining the original cause for internal diagnostics.
Unrelated programming/runtime errors still propagate. A real Fetch rejection test complements
the MockEngine contracts; cancellation remains controlled by the originating coroutine.
