# Native organization discovery and contacts

`CollaborationSpaceOwner` carries a native parent and a distinct space ID. It does
not substitute the space into an HR company ID or change the original physical SDK
namespace. The new repository and use cases read organization metadata without
depending on the selected HR workspace. Public contact company labels grant no
permission to open another company's HR records.

`DiscoverCollaborationSpace` captures the expected current native account, audience,
local request session and remote parent before the request and rechecks them after
both successful and failed responses. A successful old-session response cannot be
published after replacement, expiry or failed current-session acquisition. Native
Android/iOS callers are required. Cancellation propagates and requests have a finite
15-second lifetime; no discovery or transport retry loop is added.

The server's observation lasts at most60seconds. The client translates its duration
from local request dispatch, including network time, and clips it to both the
original and latest parent deadlines. A monotonic deadline and conservative wall
clock checks prevent rollback or a changed binding from extending that budget.
The observation grants no SDK device, room, enrollment, content access or local read.
Every later server operation still reacquires its own current admission.

`ReadCollaborationSpaceContacts` acquires one explicit page with literal trimmed
search text (at most80characters), an optional canonical account UUID cursor and
limit1–50. Each result has at most eight distinct public company labels and an
explicit overflow flag. The repository copies DTO values and validates caller
exclusion, IDs, field sizes, ordering, unique accounts and progressing cursors.
`nextCursor=null` stays terminal; no additional request is scheduled by the use case.
Its original parent deadline and15-second monotonic budget also bound publication.

The API service uses the existing authenticated native HTTP client and `forSession`
ownership. Paths are `/collaboration/spaces/current` and
`/collaboration/spaces/{spaceId}/contacts`; no company path or company-selection
operation is involved. Response limits are4KiB for discovery and768KiB for contact
pages, with smaller structural bounds after decoding. Datasources preserve raw
exceptions; the repository encloses acquisition, validation and mapping in the
shared `safeApiCall` boundary. Server failure classifications remain intact.

The [backend contract](https://github.com/fajarxfce/hris-platform/blob/5f9896f72bb047759ca2378247995eac9b1a533a/docs/collaboration/native-space-http.md)
remains disabled for product group rollout. This increment introduces no contact
replica, UI route, device command, SDK owner migration or push handling. In particular,
it does not make the current company-scoped chat UI an organization-wide interface.

[Validation](measurements/collaboration-space-discovery-2026-11-04.json): root
`:check` and Android-host pass in398.202s with3,899 unchanged inputs and2,463 fresh
cases (615 app,164 presentation,491 data desktop,247 Android-host,946 domain).
Fourteen unique new cases exercise admission/cursor bounds, cancellation, expiry,
session replacement, clock rollback, response identity and actual MockEngine HTTP
ownership; the six data cases also run on Android-host. An opt-in annotation on the
new domain test is the only subsequent change before a second successful root gate
(106.324s,946 fresh domain cases; unaffected tasks reused). Three assertions are then
added to the application container test, and both focused binding tests pass in
69.803s alongside format, architecture and detekt. Production code is unchanged
across all three gates. All sessions are closed. HTTP mocks do not establish new
backend/provider or Apple-device acceptance.

Next: encrypted local group-directory ownership and denial retention, durable
native identity/device setup commands, retained SDK adoption and native hard-parent
recovery, then product UI and group push/reply routing. Keep original history and
uncertain commands through those transitions. Actual FCM deduplication while old
and group pushers coexist remains required. No phone/live update accompanies this
transport increment; all54 communication PRD groups remain open.
