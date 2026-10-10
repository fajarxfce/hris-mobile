# Content encryption

`core/securestorage` exposes the raw `ContentCipher` contract. Its AES-256-GCM
adapter uses the platform provider from Kotlin Cryptography 0.6.0: JCA on the JVM,
CryptoKit on Apple, and WebCrypto in the browser. Each encryption uses a fresh
provider-generated nonce and the full authentication tag. The versioned binary
envelope authenticates its version and caller-provided associated data.

Repositories coordinate this adapter with their credential and database stores;
none of those datasources calls another datasource. Use cases own access, expiry,
recovery, and synchronization policy. Keep encryption, decoding and entity mapping
inside the repository's storage failure boundary. Authentication failure is an
error, never an empty record or a reason to silently generate a replacement key.

Use a separate credential record for a content key, such as `replica-key`, rather
than overwriting the session credential. Android and desktop credential stores,
and the iOS Keychain adapter, accept a validated record name. Their default remains
`session`, preserving existing installations. One owner coordinates each record's
creation and writes. A browser memory credential store must be a separate instance;
its key does not survive page closure and is not durable browser authentication.

Associated data must bind a record to its account, company, collection, generation,
resource ID, version, and envelope purpose. Encode fields unambiguously. Encrypt
checkpoints/cursors as well as private content. Database indexes may contain only
the non-sensitive identifiers and ordering metadata needed for bounded queries.
The [replica store](replica-storage.md) requires ciphertext from its caller.

The adapter accepts a 32-byte key, 1–4,096 bytes of associated data, and at most
96,000 plaintext bytes. It copies caller-owned buffers before suspension and
clears its owned key/plaintext buffers when finished. This does not promise that
the platform cryptographic provider or garbage collector erases all memory copies.
Cancellation is checked before returning a result; caller-owned buffers remain
the caller's responsibility. No retry, permission flow, key rotation, or recovery
policy is hidden inside the cipher.

Contract tests run against the real JVM and browser providers: nonce freshness,
scope/key mismatch, tampering, bounds, cancellation, and storage failure mapping.
Apple provider execution requires its platform test/build environment.
