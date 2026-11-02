# Incoming push priority recovery

The native recovery worker gives one retained push hint a bounded 20-second turn
before its ordinary fair company sweep. The existing 90-second processing budget,
180-second caller bound and 50-second company turn remain. Priority failure still
allows the normal company turn. At directory completion, an unfinished priority
keeps the independent global wake pending; healthy continuation schedules another
turn and failures retain normal retry behavior. Priority does not reset the ordinary
company or room seek.

A hinted company/device acquires the existing server admission directly, without
scanning the device directory or inventing a device version from the push revision.
The new acquisition shares the original single execution slot, 15-second timeout,
parent checks, confirmed-denial retention and conservative wall/monotonic deadline.
The response must match the current native family, account, company and expected
device. The directory path still enforces its observed minimum device version.
Native client headers and subsequent setup acquisition check platform and active
device state before opening local projections or SDK access.

Incoming priority shares the incoming processor's mutex and grant-bounded 15-second
budget. It acquires setup, resolves the admitted summary by room, then independently
reads the current room binding. Its exact Matrix ID must equal the hint before any
runtime or timeline opens. A room turn remains bounded to 12 seconds; lease cleanup
is joined on cancellation, failure and completion. No automatic enrollment,
provisioning, workspace selection or outgoing command is involved.

The requested anchor is acquired through the existing original-history path.
`ReadCollaborationHistoryEvent` then checks an indexed retained original alias or
confirmed tombstone under local owner and denial gates. Missing or unresolved facts
retain the hint. This point read does not require fresh credentials, scan history,
start the SDK or advance either Browse or Incoming traversal. It validates the exact
event ID, sender and timestamp/tombstone shape. Only a verified retained fact or
ineligible scheduling metadata can complete the exact captured queue snapshot.
Denied, expired or uncertain acquisition retains the hint and its global wake.

The first implementation completed every `Blocked` result. Review identified that
this included ordinary local native-credential expiry. A new real-container
regression reproduced `Complete` instead of `Blocked` on unchanged production
sources. The processor now retains every unsuccessful result; the regression also
asserts that no HTTP/SDK work occurs and the exact owned priority is unchanged.
This correction was made before committing or deploying this increment.

Validation covers fresh hinted admission, cancellation and shared capacity, exact
local facts, actual DI/SQLite recovery with no cached list or workspace, mismatched
room binding, confirmed denial, unresolved/focus-capacity retry, a newer push during
acquisition, container restart and full-worker fairness/global-wake retention.
[Measurements](measurements/chat-priority-processing-2026-11-02.json) retain source
and report hashes, corrections and the precise native/hosted scope.

The provider in application tests is an owned SDK double. This is not live FCM,
APNs, OS notification, native SDK priority, load/latency or phone acceptance. Per-hint
retry ordering under sustained failures remains performance work; the fair full
sweep continues other companies/rooms. Notification privacy/read/mute projection,
durable display/actions and encrypted inline replies still follow this acquisition
path. The private runtime and installed phone remain unchanged by this increment.
