# Accepted SDK8 native Apple candidate

The native candidate is accepted from isolated commit
`ad96a35a8d4ea2f931476a906a4183fb6e1f3c5e`, run43370071243/job131703523190.
Its exact GitHub artifact14011628218 is290901638 bytes with SHA256
`b8dfea189c802558ecc5c1b9f20354337375cb5d9d8342426933c3eaf2ece040`.
The collector independently verified the GitHub digest, ZIP CRC, every frozen package
and proof file, all five architectures and required exports. Collector4632 is reaped.

All five actual macOS Swift cases passed in the real XCTest XML: original raw-key
continuity, passphrase identity and wrong-key refusal, missing-store noncreation,
parent-symlink refusal after a successful direct read, and listener/observer lifetime.
Process-owned fixture cleanup passed. The iOS device and simulator products both linked.
The recheck took343.438 seconds and reused the already built native package; no Rust
rebuild or Android SDK8 change occurred. This is not physical iPhone execution.

The investigation retained all failed candidates. Rust1.96 crate names required complete
crate ownership during SQLite symbol localization. Foundation-normalized paths retained
an OS symlink spelling rejected by SQLite NOFOLLOW; native fixture paths now use POSIX
canonical strings. The fixture root lives until the Swift child exits, avoiding races
with asynchronous SQLite pool teardown. Finally, SwiftPM6.1.2 emits XCTest XML only through
its parallel runner; exactly one test worker preserves sequential execution. The previous
command is red, 26 builder plus nine recheck Python guards pass, and a missing report still
fails. None of the native identity/refusal assertions was replaced with mock behavior.

The owning source remains the isolated `fix/apple-sdk-build-budget-20261112` branch.
Main application integration, immutable Swift packaging and the actual Apple SDK bridge
are the next work. This acceptance does not publish a release, activate the Apple runtime,
enable live Group chat or close a communications PRD acceptance group. Keep the original
stores and all Android artifacts unchanged. The complete archive and logs are retained in
Mobile `.work/sdk8-apple-xctest-report-2026-11-13/hosted-43370071243`.

Evidence: `docs/measurements/collaboration-sdk8-apple-native-2026-11-13.json`.
