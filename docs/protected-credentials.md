# Protected credential records

`AtomicCredentialStore.writeIfAbsent` creates a raw record without replacing an
existing value. This supports content-key creation when multiple owners start at
the same time. Repositories still own serialization, key-loss handling, encryption
context and failure mapping; the store does not choose an account or retry policy.

Android combines a per-record OS lock, AtomicFile and Android Keystore. Desktop
uses the same OS lock around the configured credential manager. Contention fails
immediately with a technical error; there is no spin loop or hidden retry. Apple
uses Keychain insertion and reads the original item after a duplicate result.
An unreadable existing record must not be treated as an absent key.

The explicit `BrowserProtectedCredentialStore` uses a non-extractable WebCrypto
AES-256-GCM key. Its key, nonce and authenticated ciphertext form one IndexedDB
value; competing insertions retain the first complete record. Associated data binds
the database namespace and record name. Authentication tokens remain in memory.

Browser persistence requires a secure context and is subject to origin storage
eviction. Same-origin code can use the stored CryptoKey; non-extractability does
not protect against XSS, a compromised origin or an attacker controlling the
browser profile. This adapter is not an OS or hardware credential vault.

Records are bounded to 64 KiB of UTF-8 before encryption; a platform vault may
impose a smaller limit. An admitted mutation may finish when its caller is
cancelled, but cancellation still propagates. Recovery reads the retained value
instead of generating a replacement. Browser owners close pending connections,
reject use after disposal and bound database-open/transaction waits.

JVM and actual-browser tests cover competing creation, reopening, key
non-extractability, namespace mismatch, tampering, byte limits, cancellation and
disposal. OS-lock tests check contention and cleanup after failure. The Android
dev build passed; these checks do not establish Apple or desktop vault behavior
on a physical device.
