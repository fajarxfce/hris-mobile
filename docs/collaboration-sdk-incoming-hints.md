# Response-owned Matrix timeline hints

The existing successful sync response now exposes its own previous/next cursor pair and bounded
room/event locators. The producer captures `previous_batch` from the actual outgoing `since`,
including explicit and initial sync settings. A later SDK position cannot relabel an older
response. `stored_sync_position_v2()` reads only the locally retained position for detecting a
lost application handoff after restart; it makes no server request.

`SyncTimelineChangesV2` contains at most 64 rooms, 64 inspected timeline events per room and
256 retained event IDs per batch. Locators are limited to 1,024 UTF-8 bytes. Event IDs keep their
provider order, deduplicated only within that room and batch. Content, keys and account data do
not cross this new interface. Typing/receipt-only updates produce no timeline demand. Encrypted,
redacted, unknown and timeline state events retain their IDs for the admitted domain consumer.
The pre-existing membership ID lists remain unchanged.

A provider-limited timeline, missing/oversized ID, repeated-ID flood or local event bound marks
that room limited. An empty limited room still requires recovery. Missing rooms or conflicting
membership due to a bounded projection sets `rescan_required`; it cannot mean complete history.
The app must durably retain recovery demand before acknowledging the producer position. An
SDK position newer than the application's checkpoint indicates a possible gap after crash,
cancellation or failed persistence. No additional sync loop, room listener or client is created.

The source patch applies after all seven pinned SDK6 patches and reproduces the exact five
changed source files. The tests include two real consecutive sync requests with delayed
conversion of the first response, explicit/initial `since`, SDK cache retention before response
projection, SQLite reopen after losing the response, cancellation, capacity and ephemeral-only
traffic. Local FFI and core tests do not claim Android/Apple native execution.

Application demand/checkpoint persistence, runtime ownership, unknown physical-room admission,
key-arrival signals and incoming push routing remain integration work. The current inventory
scanner reads local owned bindings only; it cannot discover an unknown server room after losing
its hint. A future gap recovery path must cover that separately under current server admission.
No artifact version, dependency, live deployment or phone latency changes are included.
All 54 communications PRD acceptance groups remain open.

[Source manifest](../tool/collaboration-sdk-patches/sync-incoming-hints-source.json).
[Measurements](measurements/collaboration-sdk-incoming-hints-2026-11-10.json).
