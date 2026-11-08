# Direct transport release68 device evidence

Release 68 from `ca0b05cc83d304a17d33f47072053ff36727d9ad` is accepted on the
retained Bima emulator and installed in place on the authorized phone. The remote
APK SHA-256 matches `36fd8913cfba73c238f9fa85b57341f1d7f524827c7b7f0018213791715deb8d`.
The non-debuggable dev release preserves the existing signer, app identity,
first-install time and data. No phone UI was launched or driven.

One new original was acknowledged once, remained above the composer, and survived
a cold offline restart with previous releases 67 and 66 messages and older history. The exact
synthetic unsent draft was restored and appears in both the thread and chat-list
preview offline. Four screenshots were actually inspected. A ten-second passive
check retained the process with no fatal lines. The emulator is stopped; keys and
data are retained. Never resend this diagnostic original or the retained draft.

The 180.142-second request window contained zero 403 responses. The optional default
secret-storage key returned two 401 responses, one refresh succeeded, then two 404
responses; no repeated 403 loop was observed. Three actual combined-transport reads
returned 200 with 38, 30 and 53 ms to headers. One encrypted send returned 200/311 ms.
Counts use fixed route buckets on the emulator's gateway connection; no request
body, credential or raw owner path is retained in this evidence.

Tap-to-encrypted-request was 2,957 ms and tap-to-response-headers 3,268 ms on the
same host monotonic clock. Coarse UI polling first observed Sent at 5.091 seconds.
Local handoff took 85 ms, scheduling 4 ms, and the enclosing local submission 284 ms.
Worker intervals were composer recovery 455 ms, legacy recovery 26 ms, timeline
entry 397 ms, staging 455 ms, delivery 1,080 ms, retirement-history acquisition 707 ms
and command retirement 157 ms. ProcessRooms 3,127 ms overlaps its nested stages;
these durations must not be added. Guest log timestamps do not align to host taps.

The smaller metadata request sequence is verified, but this sample does not show
an end-to-end latency improvement over release 67. HTTP transport reads account for little
of the staging time. Continue measuring local ownership/storage and native queue
work before claiming the pending delay resolved. No controlled comparison, physical
phone outbound latency, native Apple Matrix or network-inspection overhead is
accepted here. Existing release 66 UI/inspector fingerprints match; no new video or first-
frame measurement is claimed. All 54 PRD groups and SDK and library publication remain open.

[Exact source, runtime and installation evidence](measurements/collaboration-direct-transport-release68-2026-11-08.json)
retains artifact hashes, the 3,947-case final source gate, request counts, phase
timings, actual screenshot review and 27.878-second phone install verification.
