# Existing private crypto-account reader candidate

The candidate adds a synchronous local SDK entry point that reads an existing encrypted
SQLite account and derives its public Matrix identity from the restored private Olm
account. It never constructs a Matrix client, restores a session, generates an account
or cipher, migrates a database, checkpoints it, or sends a request. This is needed before
adopting an original store whose public metadata alone cannot establish key possession.

`read_existing_crypto_store_identity` requires an existing regular crypto database and
opens it with SQLite READ_ONLY/NOFOLLOW. One read transaction obtains the existing schema
version, cipher export and encrypted account. It includes committed WAL contents and
ignores uncommitted changes. It supports the current SDK schema explicitly; it does not
upgrade older or future schemas. SQLite may manage WAL shared-memory bookkeeping, so
this is not a claim that no sidecar activity can occur.

The reader reuses `StoreCipher` and the SDK encrypted-value/pickle codec. The new bounded
import method rejects zero/excessive PBKDF work before derivation; existing `import` keeps
its original behavior and format. Inputs are bounded to a 4 KiB passphrase, 64 KiB cipher
export and 8 MiB account record, with a 250 ms SQLite busy timeout and at most one million
PBKDF rounds. These are resource bounds, not a measured end-to-end latency promise.
No plaintext account representation or private key is returned.

The FFI function `read_existing_crypto_identity` returns only Matrix user/device and
Ed25519/Curve25519 public keys. Failures use closed categories without paths or raw record
messages. Call it off the UI thread under the original exclusive directory lease, and
retain that lease until the synchronous native read has finished even if the caller is
cancelled. Application use cases must independently validate the local account, immutable
registration, original namespace and expected fingerprint. This supplies no live native
parent, server admission, device verification or migration completion.

## Reproduction and validation

Apply [existing-crypto-identity.patch](../tool/collaboration-sdk-patches/existing-crypto-identity.patch)
after the four SDK 3 patches on upstream `8de215a0aa0026afa5754ae5f05a6d168d1a1d60`.
The shipping build manifest and SDK 3 distribution are unchanged. This additional patch
is a candidate, not part of the published SDK 3 artifact.

[Evidence](measurements/collaboration-sdk-existing-identity-2026-11-06.json) records
11 cipher tests, six real encrypted SQLite/WAL cases and one real FFI case passing on
locked Rust dependencies. Two FFI compilation attempts failed on test-only import paths;
the final import uses `matrix_sdk_base::crypto`. All production sources match across
those runs. Clean upstream plus five patches reconstructs all 1,214 final tested files.
The first reconstruction needed new-file mode headers; correcting patch headers changed
no tested source.

Run the cipher library tests, `crypto_store::existing::tests` in `matrix-sdk-sqlite`
with `crypto-store,bundled`, then `existing_crypto_store::tests` in `matrix-sdk-ffi`
with `sentry,experimental-x509-identity-verification`. The local driver and full commands
are supplementary evidence; the patch and hashes are retained here in Git.

ARM64 completed in 645.717 seconds with all 1,214 source inputs unchanged. Its native
library has four 16 KiB-aligned LOAD segments and the exact new function plus all three
existing recovery methods. Nine Kotlin files were generated from that binary; only the
main SDK binding changed. [Android evidence](measurements/collaboration-sdk-existing-identity-android-2026-11-06.json)
and the separate `android-existing-identity-build.json` retain this candidate pair.
The build/package drivers accept that filename with `--manifest`, and require its
additional native function and compiled JVM API. Four build-driver cases pass, including
rejecting missing or prefix-only function symbols. The candidate workflow selects it;
the shipping manifest and dependency remain SDK 3.

x86_64 subsequently completed in 612.261 seconds with the same 1,214 source inputs,
all nine Kotlin hashes identical to ARM64, and the same entry-point/alignment checks.
ARMv7 then completed in 616.798 seconds with identical sources/Kotlin, exact symbols
and four 4 KiB-aligned ELF32 segments. The x86 ABI, actual Android consumer, private-
identity repository/use-case integration and Apple runtime support remain required. Every changed
binary must use a new SDK version; never replace SDK 3 assets. Phone 50 and its pending-
send report remain unchanged by this source-only candidate. Continue original-parent
recovery, stop/join, freeze/drain/adoption and the remaining communications PRD.
