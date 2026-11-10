# Stored room-key change hints

The existing SDK encryption client can now expose already-stored room-key changes through
`subscribe_to_room_key_changes`. The subscription owns its Encryption/client reference and the
existing TaskHandle owns cancellation. The application must fence callbacks while stopping and
wait for the handle to finish before retiring the registration. No second client or sync loop
is created. An absent encryption machine returns no subscription, explicitly.

Each notification contains only physical room IDs. It inspects at most 256 key records and
retains at most 64 unique rooms, with a 1,024-byte bound per room locator. Keys, sender keys and
session IDs do not leave this interface. Duplicate rooms collapse within one batch only. A later
notification for the same Megolm session remains relevant: a newly imported key can cover an
earlier message index and unlock older content.

Registration immediately requests reconciliation of retained missing-key work, because the
SDK broadcast has no durable replay. Actual broadcast lag or capacity loss also produces an
explicit reconciliation hint. Empty successful updates remain quiet. Reconciliation does not
authorize all-history fetching, token refresh or another transport; current application admission
and durable pending-reference processing remain separate responsibilities.

The tests include actual SDK key imports at indices 10 then 0 for the same session, two distinct
notifications and SQLite reopen proving index 0 remains available. A paused real callback lets
32 imports overflow the SDK's ten-slot broadcast, then receives explicit reconciliation and
subsequent room changes. Cancellation and TaskHandle drop release callbacks and owned references.
Unavailable crypto creates no subscription or network request. After existing SDK initialization
finishes, subscription and cancellation add exactly zero network requests.

The final full FFI suite passes 88 cases, preserving all previous 78 cases including response
cursor/cache/crash-gap coverage. The source patch applies after the response-owned sync hints.
This is source validation, not an Android/Apple artifact or application integration. Owning app
consumers, pending-reference reconciliation, unknown-room admission, generated binding pairs
and native device acceptance remain required. All 54 communications PRD groups remain open.

The FFI crate also had an unconditional dependency on experimental search, despite exposing a
separate optional feature for it. This pulled Tantivy's native filesystem dependencies into a
WebAssembly build with default features disabled. Search activation now belongs to that existing
feature. It remains enabled by default for native builds; their production dependency feature
graph is byte-identical before/after. The Cargo lockfile is unchanged.

A broader full-FFI WebAssembly check remains unaccepted: after removing the accidental search
activation it reaches an existing unconditional tracing-appender/symlink dependency that requires
a native filesystem. The core SDK with IndexedDB already compiles at unchanged core source;
that result does not claim a working browser FFI runtime. This limitation remains explicit work,
separate from native Kotlin/Swift bindings and application integration.

[Source manifest](../tool/collaboration-sdk-patches/stored-key-hints-source.json).
[Measurements](measurements/collaboration-sdk-key-hints-2026-11-10.json).
