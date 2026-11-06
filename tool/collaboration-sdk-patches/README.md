# Matrix raw-event binding under validation

`raw-event.patch` applies to Matrix Rust SDK commit
`8de215a0aa0026afa5754ae5f05a6d168d1a1d60` (Apache-2.0).
It is an isolated SDK-source increment, not a shipping dependency change.

The FFI point reader preserves exact JSON after the existing SDK decryption attempt,
returns the SDK decryption classification, checks the requested event ID, and caps
UTF-8 output at96,000 bytes. A point fetch does not write the event cache: sync owns
that persistence. A successful decryption does not assert sender verification.

[Validation evidence](../../docs/measurements/collaboration-sdk-raw-event-tests-2026-11-06.json)
records nine passing Rust tests on locked dependencies with Rust1.96. Tests cover
unknown original/edit/reply/forward metadata, encrypted reads, missing keys, SQLite
reopen without another event fetch, cancellation, identity and byte bounds.

Before consumption, build paired Kotlin/JNI and Swift/XCFramework artifacts from
this exact source with pinned toolchains, record checksums and ABIs, and prove the
native HRIS edit exchange under existing owner/admission checks. Android26.10.23
and Apple26.10.27 remain unchanged. Local history never waits for a point fetch.
