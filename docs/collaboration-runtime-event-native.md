# Exact encrypted events across native runtime adoption

The actual Android SDK6 proof passes in 125.992 seconds, including a 40.727-second
isolated emulator boot. It creates two fictional users and four encrypted events:
one original from each user and two replacements of the sender's original.
A fifth event redacts the peer's original. This proof exercises the public installed
runtime event repositories, preserving physical ownership through logical Space adoption.

Before explicit peer synchronization, the original is classified as unable to decrypt.
After synchronization it retains its original text, logical ID and canonical content hash,
including an unknown nested content extension. Replacements retain their own event and
operation IDs while targeting the original. The redacted target retains its original
author independently of the different redaction actor. The latest replacement stays the
provider head after both SDKs close and the original device/store are adopted. No new
private key, device, writer, retry or synchronization is created by an exact event read.

The fixture performs explicit setup sends and synchronization. Since the product gateway
does not expose a redaction mutation, fixture setup sends the real redaction directly to
Synapse under the same existing sender/device. The later public-key fingerprint check
passes. This is test setup, not acceptance of product delete-for-everyone behavior.

The same experiment reproduces two failures in the old history reader: an edit at the head
returns Storage through Company and Unexpected through Space, with no original window.
The SDK presentation timeline folds replacements into originals, while the old source
requires the requested anchor to remain visible as a separate item. The raw event capability
succeeds. The test records the old failures rather than accepting them as desired behavior;
the history reader is not fixed by this increment. Recovery must acquire explicit anchor
provenance without treating replacement IDs as original acknowledgements or discarding the
surrounding originals needed after lost callbacks.

The bounded trace records two native requests including one bootstrap replay, and 26 Matrix
requests including four encrypted sends, three syncs and two secret-storage reads. One server
5xx remains unresolved; its route and cause have not yet been established. This result does
not claim an error-free history path or representative phone latency.

Three Mobile proof files and three server fixture/driver files change. Mobile proof build,
20 existing unit cases and quality checks pass in 109.339 seconds. Server test-source
compilation and quality checks pass in 84.146 seconds; that compile step executes zero JVM
tests. Four new Python evidence validators and 18 unchanged driver ownership cases pass.
The subsequent native test is one executed JVM case with zero failures, errors or skips.
Strict source/report/APK/SDK audits pass. Product f64e4713 remains unchanged from its
10,203 verified local cases.

The emulator uses 2560MiB and two cores, with a precompiled 768MiB server harness and no
concurrent compilation. It is stopped after the run. The immutable 26.10.23-hris.6 SDK is
verified by checksum. No employee phone, live server/schema, product incoming routing,
Starter policy or Apple Matrix runtime changes are included.

[Measurement](measurements/collaboration-runtime-event-native-2026-11-10.json).
