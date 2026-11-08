# Composer release 67 device evidence

Release 67 from `b4b25259` is accepted on the original Bima emulator and installed
in place on the authorized phone. The installed SHA-256 matches the verified APK:
`0fb9b559e7b0bc92f5212f35d454f01a812c9956e7ab4d9a9acedf6c89b52565`.
Application identity, first-install time and data are preserved. No phone UI was driven.

One new original was submitted and acknowledged, with one visible bubble above the
composer. Input stayed focused with the software keyboard open. The known synthetic
draft was restored without sending it. Four reviewed screenshots and cold offline
restart retain the new message, three prior release66 messages, older history, draft
and inbox draft preview. The original emulator was stopped with its data and keys intact.

During the 180.416-second passive window there were zero 403 responses. Default-key
reads returned two 401 responses, one refresh succeeded, and the subsequent two reads
returned 404. The single encrypted send returned 200 with 197 ms to response headers.
These are fixed-route packet counters, not a complete HTTP reconstruction or a claim
about all phone traffic. The absent optional default key does not imply a failed send.

Same-host monotonic measurements put tap-to-request at 2,663 ms and tap-to-headers at
2,860 ms. Coarse UI polling first observed Sent after 4.881 seconds. Local handoff took
90 ms, scheduling 2 ms, and the enclosing local submission 375 ms. The worker recorded
new-composer recovery 296 ms, legacy recovery 18 ms, timeline entry 400 ms, staging
420 ms and delivery 1,038 ms. Retirement-history acquisition took 555 ms and command
retirement 263 ms. Nested intervals overlap and must not be added.

This source increment measures existing behavior. One quiet send is not a controlled
comparison with release66's three-send burst, and physical-phone pending latency is
still unresolved. Scheduling did not explain the delay in this sample. Repeated live
room/device reads remain a candidate for reducing pre-send work; current scope,
revocation and immutable-original guards must be retained.

[Exact evidence](measurements/collaboration-composer-release67-2026-11-08.json) includes
the 5,759-case source gate, signed packaging, native SDK5 byte verification, runtime
measurements, actual install and evidence hashes. Inspector/UI source fingerprints are
unchanged from accepted66; no new OS notification, video or first-frame run is claimed.
No native Apple Matrix or capture-overhead benchmark, nor SDK/library publication, is
accepted by this check. All original54 PRD groups remain unfinished.
