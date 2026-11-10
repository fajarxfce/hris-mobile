# Native admitted provider-page acceptance

The Android fixture now acquires provider metadata through the registered
`AcquireCollaborationSpaceProviderHistory` use case. Each page obtains current conversation
transport and retains its position with every metadata reference. Repeating the use case
once its saved position is exhausted makes no additional HTTP request or storage update.

Three native cases pass in 226.299 seconds. The event case reads two provider pages and makes
32 Matrix requests, unchanged from the earlier raw-page fixture: four encrypted sends,
three syncs, two secret-storage reads and two metadata pages. Native control traffic is nine
requests, including four conversation transports. The only increase is the two current
transport admissions required by the two pages. The extra exhausted invocation adds zero HTTP.

Fifteen provider references and thirteen pending references retain their exact frontier,
physical provider and pending hashes when another process opens the database offline.
Original, edited and redacted content stays identical; no SDK runtime opens during that local
read. Existing original and mixed recovery cases still confirm exactly two and three provider
events, preserve original transactions with an offline recipient, and survive process loss
followed by an offline Sent projection. These are isolated fixture measurements, not phone latency.

The proof APK passes 20 unit cases and quality checks in 112.270 seconds. Backend fixture
compilation passes in 79.817 seconds; its unchanged protocol code retains 35 accepted Python
cases. Strict audits verify source, library, SDK and APK hashes and all previous case identities.
The emulator uses 2,560 MiB and two cores, runs without another compiler, and is stopped afterward.
The employee phone and live backend are untouched.
[Sanitized measurement](measurements/collaboration-space-page-native-2026-11-10.json).

The independent incoming consumer, durable wake and pending-content orchestration remain
implementation work. This acceptance enables no new product route or worker. All 54
communications acceptance groups remain open; native Apple Matrix is still separate work.
