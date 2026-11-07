# Durable Android runtime handover

[Closed original-store handover](collaboration-existing-store-handover.md) adds a
separate existing-only path after login replacement. The open-client path below
retains its original guards.

[Actual original queued-key continuity](collaboration-space-sdk-history.md) now
passes with the retained SDK store across native server completion and two process
deaths. Its fixture owner does not adopt this product runtime or integrate push.

This local mechanism stages an exact transfer intent. It does not call the group
HTTP API, publish provider keys, freeze the server, adopt a group runtime or enable
cross-company chat. Those application workflows remain to be integrated.

`CollaborationRuntimeRepository.handoverIntent` reads owned protected metadata
without opening the SDK or acquiring its directory. Credential age and replacement
of the local login identifier do not hide an existing intent. Its operation, target
space/device, expected device version and canonical key fingerprint are immutable.
An exact replay is read-only; changing any target coordinate fails.

First staging requires the current published SDK client. A child-owned local key
observation keeps its native client alive while the public fingerprint is computed.
The registry pins that exact observed client, preventing a replacement between
observation and exclusion from inheriting the result. One short registry section
excludes all foreground/background acquisitions for the original store and retires
its existing resources. Pending constructors remain reservations until their full
cleanup; an empty client list is not proof of drain. There are at most five gates.

The caller waits for complete old-client shutdown, then acquires the exclusive
original directory and writes protected record schema2. It rechecks the original
provider user/device. The original company/native namespace, passphrase, bootstrap
facts, tokens and SDK files remain in place. Only the schema, handover intent and
credential revision change. Old SDK callbacks have finished before this write, so
they cannot overwrite the intent with a schema1 snapshot.

Waits are bounded. A pending or failed shutdown keeps its native resources and file
lock owned by the application. Temporary exclusion ends in `finally`; after a
successful write, the durable record excludes every legacy restore/install path,
including process restart. Legacy session conversion and token updates also reject
schema2. The previous strict decoder rejects the new record format; actual old-APK
downgrade behavior has not been exercised.

Cancellation before commit leaves the old protected format available for explicit
fresh preflight and restore. Cancellation after commit can leave a durable intent
although the waiter received no result. The next attempt reads and resumes that
same target. A failed or uncertain call must never be treated as permission to
freeze the server.

The future owning use case must finish provider key publication/readback before
staging, then revalidate current native/group/client/MFA admission before server
freeze and completion recovery. A public fingerprint or local intent is not an
access grant. Keep uncertain transaction IDs, drafts, history, receipts, notifications
and deep links bound to their original owned records during later group adoption.
Do not substitute a space ID into the original SDK company namespace.

[Local validation](measurements/collaboration-runtime-handover-2026-11-04.json) passes
the root/Android/proof gates,463 browser cases and actual67.337s emulator recovery.
[Hosted regression](measurements/collaboration-runtime-handover-hosted-2026-11-04.json) also passes: 3,118 portable, 1,219 Apple-simulator, 241 Android-host, 18 proof-host and 5 native-HRIS cases, plus both Android/web protocol modes. iOS host compilation passes; actual Apple Matrix/APNs/CallKit remain unfinished. Record and registry
checks cover immutable replay, strict schema, late constructors, replacement-client
races, gates, borrower interruption and ownership limits. Android metadata tests
cover expiry, local login replacement, cancellation and old-path rejection. The
separate debug emulator probe exercises real encrypted SDK/Keystore writes and
process restart; it uses inert credentials and no reachable server. It must never
be installed on an employee's phone. Provider publication, product notification races
and group runtime/message continuity still require end-to-end acceptance.

The tracked `LocalRuntimeHandoverProbe` runs through `MatrixProofInstrumentation`
with scenarios `local-handover-stage` and `local-handover-resume`. Use only an owned
emulator and the debug `collaboration-proof` APK. Clear that fixture application
before staging, force-stop its process between scenarios, then read the bounded
`local-handover-stage.json`, `local-handover-result.json` and `instrumentation.json`
reports from its `files/protocol-proof` directory. The driver must verify the process
is absent between phases, retain those reports, clear the fixture application and
stop only its own emulator. No product APK or employee account is involved.
