# Original incoming content during pagination

Company-backed incoming work now retains message content after each committed provider page. Previously, it waited for the entire provider history to end before acquiring content. The existing worker reconciles local facts, reads only bounded unresolved event IDs, confirms their outcomes, and yields the next page without retry backoff.

The domain content policy admits exact Provider and Pending captures. Latest remains inadmissible. Source, Company, physical device, room, and installed runtime checks remain mandatory. While another provider page exists, missing keys cannot retire or demote the primary task. Queue completion still requires the independent EOF and pending-reference checks.

Local validation covers 14,512 cases: 11,007 from the final full run, 1,467 passing Data cases from the preceding run with identical source dependencies, and 2,038 unchanged baseline cases. The 433 focused cases include cancellation at each content stage, changed captures, failed content acquisition, missing keys, local reuse, and physical-owner rejection under both phases. Four behavior test names were deliberately replaced; every other predecessor identity is preserved. Failed attempts and their corrections remain recorded in the [measurement](measurements/collaboration-space-original-progressive-content-2026-11-12.json).

This changes neither schema nor SDK. Native worker acceptance and hosted Apple validation are still separate work. The generated x86 proof APK is for isolated emulator tests; it is not a phone release. No backend deployment or starter policy port is included.
