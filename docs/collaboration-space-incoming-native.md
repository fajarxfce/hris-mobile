# Actual incoming batch retention

The isolated Android proof now uses the registered `AcquireCollaborationSpaceEvents` and real
Matrix SDK6 over the existing original crypto store after logical Space adoption. Two source
IDs (latest edit and redaction) retain one original message, its separate latest revision and
a peer's original-sender tombstone. Exact original/replacement content hashes are computed
independently from fictional input. The edit has a replacement fact and no original-event fact.

Replaying that same batch leaves authenticated history revision, contents and both outgoing
journal checks unchanged. A different instrumentation process then reopens encrypted application
storage after the driver removes its owned backend reverse mapping. Local admission and database
reads recover exactly the same content and revision without opening a Space SDK runtime.
The driver rejects changed identities/hashes, same-process reopening, missing release evidence,
false flags and unexpected payload fields. This is real storage/SDK acceptance, not chat UI timing.

All three native cases pass in208.285s, including40.900s emulator boot. The event fixture makes
7native API requests, including exactly2direct transports for2batches, and30Matrix requests.
Its4encrypted sends,3syncs and2optional secret-storage reads remain fixture setup/control work;
no extra encrypted message is sent. No provider failures occur. The original-only and mixed
Company/Space controls still preserve transactions across process loss, confirm exactly2/3events
with the peer offline and reopen those confirmations locally. Their prior request counts and
all earlier test identities remain unchanged.

Mobile proof build/unit/quality passes20cases in111.123s. Backend testClasses/format/architecture
compilation passes80.918s (zero JVM tests in that compilation gate), plus27Python protocol cases.
Strict audits bind both source trees, unchanged inspector source, proof APK, immutable SDK6,
report identities and fixture evidence. The fresh2,560MiB/two-core emulator is stopped; the768MiB
server harness used precompiled classes, with no heavy compiler overlap or employee-phone use.

This accepts finite incoming retention and offline reopening. Independent provider-frontier and
pending-reference progress, callback recovery and the product incoming consumer remain unfinished.
It grants no outgoing acknowledgement, cursor advancement, native Apple Matrix implementation,
phone performance result or live rollout.

[Measurement](measurements/collaboration-space-incoming-native-2026-11-10.json).
