# Architecture

[Development network inspection](network-inspector.md) is owned by application composition;
transport adapters observe actual attempts and the optional viewer renders bounded snapshots.

[Optional Android HTTP observation](collaboration-http-observation-lifetime.md) stays
inside the exact native-client lifetime; application composition supplies its adapter.

[Chat end positioning](collaboration-chat-new-message-position.md) belongs to the route
viewport owner; the page remains state-driven and older reading positions stay anchored.

[Chat authority renewal](collaboration-offline-authority-renewal.md) targets captured
selected work while preserving local reads, durable originals and separate background admission.

[Original outgoing remote echoes](collaboration-chat-remote-echo.md) preserve separate
transaction/event identities without changing transport or durable acknowledgement.

[Local pending and draft previews](chat-inbox-previews.md) share one owned bounded
command observation and keep presentation independent of transport acknowledgement.

[Owned Room request preparation](collaboration-space-room-preparation.md)
recovers original work before current native/device reads and exact fenced handoff.

[Atomic original room handoff](collaboration-space-room-successor.md)
preserves exact fenced originals and authenticated successor links in one raw transaction.

[Owned original room resolution](collaboration-space-room-request-resolution.md)
coordinates current native admission and durable evidence capture without replacing messages or SDK state.

[Durable original room non-execution evidence](collaboration-space-room-fence-storage.md)
retains exact immutable fences without changing old request envelopes or granting successor authority.

[Exact original room-request resolution](collaboration-space-room-request-fence.md)
maps retained receipts or explicit non-execution evidence under current native transport.
Durable capture and successor orchestration remain with the owning flow.

[Owned original conversation opening](collaboration-space-conversation-open-replay.md)
retains the original operation, reads normalized durable marks before dispatch and
materializes exact canonical metadata before archival. Content admission stays separate.

[Owned conversation opening preparation](collaboration-space-conversation-preparation.md)
recovers original local commands or canonical IDs before allocating a new setup intent.
Current admission and exact-ID reconciliation stay in the owning use case.

[Owned canonical conversation metadata](collaboration-space-conversation-metadata.md)
keeps immutable pair facts independent of mutable login/native identity, with atomic
encrypted conversation/peer indexes. Current content admission remains separate.

[Owned conversation command observation](collaboration-space-conversation-observation.md)
keeps local facts independent of token age and cancels old account subscriptions without
starting business retry. Preparation, dispatch and canonical projection are separate.

[Durable organization conversation commands](collaboration-space-conversation-commands.md)
retain stable setup intent, uncertainty and exact archived receipts across local login
replacement. Owning dispatch, projection and replacement fencing remain separate.

[Stable organization room requests](collaboration-space-room-stable-intent.md) keep
immutable business intent separate from rotating native proof. Receipt absence
remains uncertain; durable dispatch and explicit replacement fencing are separate.

[Original room request receipts](collaboration-space-room-receipts.md) read complete
original proof under the current native owner. Initial durable proof capture and
stable owning dispatch remain separate; receipt absence does not fence late work.

[Organization conversations and room transport](collaboration-space-conversations-and-rooms.md)
separate immutable pair intent from published room status. Native transport preserves
account-local request revisions and exact group identity; live admission and durable
command orchestration remain with the owning use cases.

[Original organization message writer](collaboration-space-original-writer.md) preserves
the physical queue namespace under exact logical runtime ownership. Current logical
device identity remains separate from the device recorded in original messages.

[Original queue matching](collaboration-space-original-queue-reconciliation.md)
keeps pure whole-inventory validation separate from the owning current-admission
read. Matching retained commands and completion evidence grants no queue mutation.

[Explicit native notification retirement](native-notification-retirement.md) keeps
OS timeout separate from observed removal and retains opaque inventory through
cancellation. The raw adapter owns this bounded OS interaction.

[Owning original metadata reads](collaboration-space-original-conversation-reads.md)
coordinate finite native admission and capture immutable bounded pages; a successful
recovery read cannot reopen a denied content projection or authorize queue delivery.

[Original conversation metadata](collaboration-space-original-conversations.md) preserves
company/conversation coordinates and independent room provenance through bounded native
organization reads. Owning publication and application-command matching remain separate.

[Native original-queue continuity](collaboration-space-original-queue-native.md) exercises
the actual SDK transaction store through interrupted adoption and offline process restart.
It does not grant new delivery or product-routing authority.

[Original organization queue facts](collaboration-space-original-queue.md) retain the
physical message namespace while observing one exact logical runtime through its
existing exclusive paused writer. Publication and delivery remain separately admitted.

[Organization runtime admission](collaboration-space-runtime-admission.md) owns the
finite current-session/device checks and final publication while preserving original
bootstrap identity through ordinary token refresh and concurrent activation.

[Original SDK adoption](collaboration-space-runtime-adoption.md) restores existing
private keys under one physical lease, retains logical organization ownership, and
keeps live publication in the owning admission flow.

[Logical organization runtime ownership](collaboration-space-runtime-ownership.md)
keeps original physical SDK slots distinct from Space lifetime, excludes legacy
borrowers and preserves cleanup through native-parent changes.

[Group SDK credentials](collaboration-space-sdk-adoption-credentials.md) preserve
the original physical store and queue identity while isolating the adopted session
behind a fixed per-lease callback namespace. Runtime adoption remains separate.

[Original runtime transfer](collaboration-space-runtime-transfer-flow.md) owns the finite
publication, private-store staging, admitted freeze and drain sequence. SDK adoption
and product activation remain separate.

[Foreground chat delivery](collaboration-foreground-delivery.md) gives the Android
process a lifecycle-owned wake path while WorkManager retains persistent background
scheduling. Both use the same admitted worker and existing SDK writer exclusion.

[Interrupted credential handover](collaboration-interrupted-credential-handover.md)
preserves the original uninitialized record in a distinct strict format after verified
private-store drain; it grants no provider publication or server authority.

[Closed original SDK handover](collaboration-existing-store-handover.md) stages an
exact target under the original private-store lease after all old reservations drain.

[Original runtime transfer transport](collaboration-space-runtime-transfer.md) maps exact
freeze/drain metadata without granting SDK shutdown or completed ownership.

[Delivery across retained device namespaces](collaboration-chat-queue-ownership.md)
keeps older uncertain commands intact while selecting current-store work before
the finite worker batch limit.

[History replay](collaboration-chat-history-replay.md) preserves encrypted records
and revisions when original messages or redactions have not changed.

[Cached room entry](collaboration-chat-first-frame.md) retains bounded screen state
under the account/company navigation graph and keeps guarded local thread reads
in the domain use case.

[Chat and contacts](collaboration-chat-hub.md) share one root destination and retain
same-owner local previews across partial list emissions.

[Offline text forwarding](collaboration-chat-forwarding.md) publishes complete encrypted batches without changing composer drafts.

[Device sync before the first room](collaboration-device-sync.md) uses owned background references and preserves server key-publication guards.

[Older-history end restoration](collaboration-chat-history-end.md) retains room metadata without advertising it as pending messages after reopening.

[Quoted conversation replies](collaboration-chat-quoted-replies.md) retain original
event references and observe admitted local targets through replica checkpoints.


[Cold offline history](collaboration-chat-offline-restart.md) verifies actual protected-store recovery and canonical reads after process death with the gateway unavailable.

[Chat header and selection](collaboration-chat-header.md) keeps transport state out of the name header and starts contextual actions from a selected bubble.

[Original chat connection recovery](collaboration-chat-reconnect.md) preserves the private store through typed SDK rejection and exclusive credential replacement, with compact contextual recovery actions.

[Local chat entry and foreground recovery](collaboration-chat-local-entry.md)
keeps cached messages independent of transport and places held replies in context.

[Contextual message selection and durable local deletion](collaboration-message-selection.md)
adds multi-select/copy/delete-for-me while retaining original message evidence.

[Original private-account identity](collaboration-space-sdk-possession.md) compares
keys derived from the existing private account with immutable original provenance;
its raw read remains under one directory lease through cancellation and cleanup.

[Original SDK inventory](collaboration-space-sdk-inventory.md) separates account-owned
protected-record/file facts from key possession, live authority and runtime adoption.

[Atomic organization bootstrap handoff](collaboration-space-bootstrap-handoff.md)
retains the original uncertainty and authenticated successor before explicit replacement
replay; the use case owns recovery and the repository maps one raw database transaction.

[Explicit organization bootstrap replacement transport](collaboration-space-bootstrap-replacement.md)
binds the complete original effect descriptor to a current-build replacement response;
durable local handoff and owning recovery are described above.

[Native organization setup owning flow](collaboration-space-native-setup-flow.md)
coordinates finite original retry/bootstrap preparation and replay, exact retry
receipt reconciliation and account-owned queue summaries without credential exposure.

[Durable native organization setup](collaboration-space-native-setup-storage.md)
retains encrypted original retry/bootstrap commands and gateway outcomes, with
exact immutable retry archives and account-owned recovery after login replacement.

[Native organization setup transport](collaboration-space-native-setup.md)
adds exact original provisioning retry and bounded group gateway bootstrap, with
actual-build wire binding and separate provider/device identity.

[Native original-device registration](collaboration-space-registration-flow.md)
adds account-owned observation, finite explicit preparation and durable replay
using the exact original operation, SDK identity and native parent.

[Durable organization device registration](collaboration-space-registration-storage.md)
retains encrypted original intent and SDK provenance across login replacement, with
a bounded account inventory over native parents and explicit uncertain outcomes.

[Selected organization identity](collaboration-space-identity-observation.md) reads
retained provider-user metadata through the current native parent, without selecting,
enrolling or granting directory/SDK authority.

[Local SDK identity observation](collaboration-local-sdk-identity.md) borrows the
existing Android runtime without opening another store, enrolling or granting
current migration authority.

[Native organization device status](collaboration-space-device-status.md) observes
current-parent metadata without enrollment, SDK acquisition or reopening directory
reads. A missing device never authorizes replacement of retained keys.

[Durable organization identity selection](collaboration-space-identity-command.md)
keeps immutable operation provenance separate from the fresh native owner of each
explicit attempt. Its account/space receipt grants no device, SDK or directory access.

[Known organization root](collaboration-space-root.md) locates an account-owned
directory cache after restart or reauthentication. It grants no live authority;
explicit directory admission and device/SDK ownership remain separate.

[Encrypted organization directory](collaboration-space-directory-storage.md) keeps
account-owned facts, cursor progress and access admission separate from native
credential lifetime and selected HR company. Its repository exposes encrypted
storage; [owning use cases](collaboration-space-directory-usecases.md) coordinate
read admission, publication and finite explicit acquisition.

[Native group discovery](collaboration-space-discovery.md) uses a distinct
organization owner over the current native parent, separate from selected HR
company and retained physical SDK ownership. Its finite metadata/contact reads
have no device, content, local-cache or product-runtime authority.

## Ownership

| Layer | Responsibility |
|---|---|
| Datasource | Raw SDK, network, OS, or storage I/O through a contract |
| Repository | Coordinate datasources, map DTOs and technical failures |
| Use case | Coordinate repositories and decide application policy |
| ViewModel | Coordinate use cases, concurrent requests, and presentation state |
| Composable page | Render state and forward actions |
| Navigation entry | Create route-scoped ViewModels, collect state, handle navigation effects |

Dependencies point inward. Domain modules have no UI, DI, network, or platform dependencies.
Identity and workspace selection are shared core domain. Auth, company selection,
and home consume their contracts. Features do not consume each other's ViewModels,
data modules, or repository implementations.

Shared HRIS [document contracts](documents.md) live in `core/documents` so employee
workflows can select evidence without feature-to-feature dependencies. Its use
case owns live admission and request lifetime; the data adapter maps metadata and
technical failures. File contents and business attachment decisions have separate
owners.

Kotlin's data classes provide value equality. StateFlow exposes immutable screen snapshots.
Each screen defines a sealed Event contract and dispatches through `onEvent`. The shared
`MviViewModel<State, Event, Effect>` registers separate typed handlers with `on<Event>()`,
exposes read-only state/effects, and provides `updateState` and `emitEffect`. Reducers are
pure state transformations. There is no universal event switch or shared concurrency policy.
The Compose runtime's state is limited to rendering mechanics and explicit resource
ownership at composition boundaries.

## Build logic and DI

Convention plugins configure Kotlin targets, Android, Compose, serialization, and KSP.
Pure domain modules publish JVM, iOS, and Wasm variants without applying an Android plugin.
Android consumers use their JVM variant. UI/data modules additionally publish Android AARs.

Every participating module has one DI entry point with Koin component scanning. Domain
use cases remain annotation-free; their constructor bindings are grouped in the owning
data module. The app installs those module registrations once. The compile task generates
Koin code; generated build output is not committed. Integration tests resolve the assembled graph.

There is one isolated Koin container per application process/window. Android's Application
owns it across Activity recreation. Desktop/iOS hosts close it with their UI lifetime;
the browser owns it for the page lifetime. Containers own HTTP clients. Navigation entries
own feature ViewModels. Android reuses the Activity ViewModelStore across configuration
changes; other hosts create a window-owned store and clear it on disposal.

## MVI delivery

The base dispatches events on Main. Handlers update state synchronously and launch work
in `viewModelScope`; each screen decides whether to cancel, drop, or allow concurrent work.
Clearing the ViewModel cancels its work and effect channel. Screens without transient
effects use `Nothing` as their Effect type instead of inventing empty contracts.

Effects use a bounded channel and one lifecycle-aware route collector. Pending effects
wait while the route is stopped and consumed effects are not replayed. This is process-local,
single-consumer delivery, not an exactly-once or persistent queue. Onboarding emits a
completion effect only after persistence succeeds; startup reads the stored completion
again after process recreation. Auth navigation follows the session state rather than a
transient login effect. Durable data and business outcomes never live only in an effect.

## Errors and concurrency

Safe functions execute an operation once and map technical exceptions to AppResult.Failed.
Their callbacks return raw values, including mapped entities. Returning AppResult from
inside a safe callback would create a nested result and is not allowed.

Safe calls rethrow cancellation and check cancellation before publishing a result.
Default diagnostics contain exception categories only; an explicit diagnostic callback
can retain the original exception for an appropriate internal sink. Never expose response
bodies, credentials, or exception messages in UI/log output.

Protected read failures remain authoritative while a retry is pending or fails.
A transport/storage error cannot replace a confirmed access denial and make
retained private data readable. Presentation stops that cache
subscription until a fresh admitted acquisition succeeds. Cache deletion remains
best effort; tests retain old ciphertext when cleanup fails to verify the gate.

Local read models remain observable after their freshness deadline. Their
`refreshAfter` timestamp is separate from operation authorization and never
causes a timer to hide content. A refresh or unavailable network does not replace
saved facts with a loading screen. Navigation/lifecycle owners request finite
acquisition; no stale-data emission starts a retry loop. Refresh never rewrites
an old timestamp or resubmits a business command. Command preparation and exports
retain explicit, operation-specific validity checks. See [local-first](local-first.md).

Mapped record failures can recover on a later storage emission. An upstream
storage-flow exception instead ends that subscription after reporting failure.
The presentation owner must reopen observation after a successful explicit
refresh, including when its outer state collector is still active. Do not turn
the data boundary into an automatic retry loop. Test source completion separately
from a bad record, and cancel both observation and acquisition on route disposal.

Sign-in drops duplicate submissions and freezes credential edits until completion.
Clearing its ViewModel cancels the request. The sign-in use case checks cancellation before
committing a successful session through the session repository. Refresh and MFA share
one credential-mutation mutex. A company selection checks current identity and uses
compare-and-set before publication. Late results cannot replace an accepted selection.
Leaving retries one concurrent metadata refresh of the same workspace. A replacement
workspace is never cleared; repeated contention returns a conflict rather than
reporting that the old selection was removed. Cancellation stops further attempts.
Repositories do not own retries, scheduling, or UI loading state.
A raw streaming adapter may own bounded protocol transport backoff within its
explicit connection lifetime. This never refreshes application read models,
replays business commands or renews credentials; see [SDK synchronization](collaboration-sdk-lifecycle.md#lease-owned-classic-synchronization).
Workers are platform application entry points that invoke `SyncTask` use cases.
The [workspace](workspace.md) use cases separate local ownership from live
revalidation. Its repository coordinates encrypted storage and raw remote access;
foreground lifetime and confirmed-access failure gates belong to application MVI.
Local bootstrap restores an owned selection and its routes before remote work.
An unchanged renewal retains navigation owners; ordinary authorization age no
longer creates a recovery route. Scope replacement or a known company restriction
still disposes protected owners.
See [native identity](native-identity.md) for the active HRIS integration and
[inbox](inbox.md) for the first encrypted HRIS feed integration and
[sync](sync.md) for the inherited worker and command contracts.

Inbox list, detail, unread counts and durable actions share an encrypted
read-admission record. A process-owned datasource buffers confirmed denials while
persistence is pending or fails. Use cases check that admission after pending
reads and commands; complete acquisition can clear only the denial revision it
observed before starting. Fact cleanup never substitutes for access admission,
and an outcome-write failure cannot erase a confirmed delivery denial.

Company data observation and refresh have shared ports in workspace domain,
implemented by each feature's existing use cases. Metadata observation shares
the feature's normal ownership and read-admission policy. Repositories decrypt
the published checkpoint without loading resource payloads. Refresh carries an
explicit workspace ID and reuses the worker's acquisition instance. Consumers
do not depend on feature repositories or presentation modules.

Pending-action observation uses a separate workspace-domain port implemented by
each owning feature. It combines encrypted working copies, immutable retained
commands and document transfer state under that feature's shared read admission.
The route observes local counts and opens workflows; it never prepares or delivers
commands. Published partition pages and complete capacity-bounded command reads
remain raw database contracts. See [pending actions](pending-actions.md).

The [work calendar and attendance](workforce.md) share account-owned encrypted
attendance days, effective schedule assignments, roster overrides and holidays.
`SyncWorkforce` owns change-feed acquisition and live admission. Its repository
commits records, directory metadata and cursor together; observation use cases
query local dates and resolve schedule precedence through pure domain policy.
An encrypted read-admission record carries confirmed denials across pages and
process restoration. A process-owned write buffer keeps that denial observable
when persistence fails. Only an admitted acquisition started after the latest
denial may clear it; ordinary age and cursor expiry never set it.
Attendance capture coordinates the generic [location repository](location.md),
current workspace and durable command storage. Permission requests, evidence
freshness and offline admission remain attendance policy. The location datasource
only performs the requested OS operation; it neither opens Settings automatically
nor starts a long-lived tracking subscription.

Overtime planning has a separate short-lived review value within Workforce.
Its use case acquires current policy, verifies owned employment and obtains the
server's versioned schedule. Repositories map that response; pure domain policies
validate requested and actual intervals. The review's lifetime is bounded by
company/session authority and carries no command or UI state.

Overtime reads use a separate account-owned replica. `SyncOvertime` coordinates
company admission, own-employment discovery, request change references and
canonical reads; its repository commits encrypted records with the cursor.
`ObserveOvertime` queries date/status selections locally. `LoadOvertimeRequest`
shares the acquisition mutex, retains each request and reuses acquired activity
pages without HTTP. Historical acquisition never renews action-review authority.
An independent encrypted admission record closes overview, request and command
reads after confirmed access denial. The repository buffers that record while
storage is pending or unavailable; use cases retain the original failure and
check admission after pending work. Only complete acquisition started after the
latest denial can reopen the shared projection. Form and detail ViewModels
coordinate this recovery before requesting a fresh review; individual reads do
not clear it. Pending commands remain stored throughout recovery.

Overtime commands use separate preparation, delivery, observation and review use
cases. Preparation validates the admitted review/request and stores immutable input;
delivery coordinates command storage, live company policy and the transport port.
Its shared mutex serializes manual and worker attempts. The repository authenticates
bounded input/outcome envelopes and maps DTOs; it does not choose retry or discard
policy. Uncertain outcomes retain their original operation and body, including after
a later rejection. Confirmed cleanup requires admitted request facts and an atomic
outcome revision check.

The [Leave page](leave.md) uses its own minimal self-service employment directory,
independent of People and Workforce availability. `SyncLeave` admits the selected
company and reconciles request/balance change references. Its replica repository
atomically stores encrypted records, directory metadata and the processed cursor.
`ObserveLeave` queries those records by employment, year and status; filters do
not acquire data. An encrypted admission record retains confirmed read denials;
a process-owned buffer covers failed writes. Only complete, current acquisition
started after the latest denial reopens the shared projection. The ViewModel owns
selection, presentation and cancellation; it never recalculates accounting
balances or sends technical API types to the page.
The preceding overview remains a migration fallback until the first complete
replica publishes. Own-request details have separate acquisition/observation
use cases and share the account-owned replica. Each request retains its full
facts and independently acquired activity pages. A fresh individual read can
publish before initial list synchronization; it does not mark that list complete.
History pagination changes the visible revision page, not current request facts.
`SyncLeave` fills older activity after feed catch-up, with a persisted frontier per
request. Historical acquisition never renews an action review's timestamp.

The [payslip workflow](payroll.md) keeps employee SELF scope explicit in
every financial API request. `SyncPayroll` hydrates the account-owned payslip and
payment collections; its repository commits full records, indexed summaries and
cursor atomically. Observation use cases query local years and individual IDs.
Stored ownership is independent of credential age and unrelated permission changes.
PDF export coordinates the
payroll read contract with the generic document-output contract; it does not need
document-catalog permissions or another feature's use case. The data adapter owns
protocol validation, exact decimal mapping and temporary byte-buffer ownership.
Route-owned ViewModels coordinate typed acquisition and export events. The
detail export lives until route disposal, including while the Android save
dialog temporarily stops the Activity; financial facts remain domain values.

The [expense feature](expenses.md) uses its own minimal employment directory and
explicit SELF reads. Its detail use case brackets composite acquisition with
the claim version; repositories map protocol facts and atomically store the
bounded encrypted result. Approval, reimbursement and activity remain separate
facts. Screen owners retain read gates and cancel obsolete filter requests.

Expense receipt export coordinates a narrow receipt-content contract with the
shared document-output contract. Its repository validates and hashes bounded
sequential chunks; its use case owns live authorization and file lifetime. Detail
keeps the export through a system-picker stop and cancels it when the route closes.

Leave preparation uses a separate read contract for effective types and the
owner-only server preview. Its use cases own date-input validation, live company
admission and the preview deadline. Datasources return DTOs; repository mapping
checks the consistency of charged/excluded dates and annual balance projections.
No reservation or request mutation occurs during preparation.

Submission is a separate durable intent. `PrepareLeaveSubmission` validates the
admitted preview and saves immutable input before transport. `ReplayLeaveSubmission`
coordinates current company policy, raw-command persistence and the remote
repository, serializing manual and worker delivery. Repositories authenticate the
stored envelopes and map protocol facts; they never decide when to retry or discard
an uncertain operation. A preview revision accompanies the original request on
every delivery, so changed server balances or schedules cannot silently replace
what the employee confirmed.

Unfinished Leave input has a separate working-copy repository and encrypted
partition. Its use cases coordinate local employment ownership, read admission and
revision checks. The creation ViewModel owns a bounded serial autosave writer;
widgets render save status and dispatch explicit retry, reload and discard events.
Submission links its immutable body to an acknowledged working revision. Canonical
reconciliation removes only that revision before retiring the command, preserving
newer edits and recoverable cleanup failures.

Overtime planning keeps the same separation between acquired facts, unfinished
local input and immutable commands. Working-copy use cases coordinate stored
employment ownership, read admission and revision checks; the route owns autosave
and leaving behavior. Its working repository shares raw storage and content-key
creation with the replica, without calling another repository. Confirmation pins
an acknowledged input revision, and canonical reconciliation removes only that
revision before retiring the command.

Feature flag definitions and evaluation policy live in `core/featureflags/domain`; each
consuming feature owns its keys and action guards. A shared refresh use case serializes
workers and sets the fetch interval. Its repository coordinates Remote Config and the
preference store; SDK adapters perform raw fetch/activation only. Flags have no UI state,
and overrides are allowed only by dev/staging use-case policy.

The typed preference store already serves as a datasource contract. Onboarding consumes
Proto DataStore through that port; its repository maps the protobuf field to a domain value.
The in-memory session datasource owns a replayed DTO snapshot. Its repository
coordinates credential storage and account cache ownership; session policy remains
in use cases. See [native identity](native-identity.md).

Durable commands use a separate [storage contract](command-storage.md). Session
persistence binds a stable audience/account key while cache activation clears only
temporary session data. Account-owned read replicas and durable commands remain
retained but require a current matching session to access them. Features own encryption, command admission, replay and conflict policy;
the database only enforces ownership, immutability, bounds and atomic revision checks.

Large file contents use the separate [blob store](blob-storage.md), with bounded
opaque blocks, transactional progress and account-owned reservations. File adapters
own encryption and mapping; use cases own import, transfer and cleanup policy.
The [file input adapter](file-input.md) owns OS pickers and transient readers;
those resources never enter a domain contract or a ViewModel.
The [file output adapter](file-output.md) owns native save dialogs, bounded writers
and disposal. Export admission and choosing when to copy remain use-case work.

The [private profile](people.md#private-profiles) has an independent account-owned
replica and content key. `SyncSelfProfiles` coordinates explicit change references,
coherent canonical reads and atomic publication; `ObserveSelfProfiles` combines local
facts with employment selection. DTO mapping validates matching account/person and
employment identities. Sync revisions remain separate from command versions.
Confirmed read admission has a durable gate and a process-owned failed-write buffer;
screen state cannot reopen private facts after a denied or incomplete retry.

## Navigation

Navigation persistence pairs the SDK stack with its admitted account/company
scope. Bootstrap renders outside NavHost until authority is resolved; an unknown
scope cannot restore a saved graph. A matching scope restores the original stack,
while a changed scope discards it before graph attachment. Unresolved snapshots
survive another recreation during bootstrap. Navigation owns a dedicated retained
ViewModelStore, independent of application state; mismatched restoration and final
owner disposal clear its entries and cancel their work. Ordinary graph changes
continue to let Navigation clear outgoing entry stores. The snapshot format must
change when existing serialized routes become incompatible.

Enum route arguments register `enumNavType<Mode>()` in the destination's explicit
`typeMap`, keyed by `typeOf<Mode>()`. The shared presentation adapter supports every
Navigation target and persists enum names. Unknown names fail decoding rather than
selecting a fallback action. Argument types compare by their enum class, so rebuilding
the same graph does not clear its back stack. Renaming an enum value changes the saved
route contract and requires a compatible restoration policy.

Features own their serializable route and NavGraphBuilder registration. The app composes
their entries and reacts to onboarding/session state. Authentication transitions replace
the root stack, so sign-out removes protected screens. Home owns its
Home/Chat/Inbox/Account tab state; changing tabs does not create independent
navigation histories. All services is a separate feature-owned route. Back pops
an inner route first, then returns a nondefault root tab to Home. Home leaves the
next Back action to the platform. Cancelling a back gesture does not change tabs.
A host-level fallback consumes extra Back actions during the returning root's
transition. It is disabled once that root resumes; destination callbacks retain
priority. This prevents a rapid second Back from exiting before the tab returns.
Its service catalog groups only implemented destinations admitted by the selected
company's modules and permissions. The catalog exposes typed service identifiers;
app composition connects them to feature-owned routes. Adding an employee service
does not require another bottom-navigation item.

App composition supplies attendance, inbox and employee-directory entries to Home
through composable slots. They retain their own route-scoped ViewModels; Home
does not depend on those feature modules. `LifecycleStartEffect` pairs entry with stop/disposal,
including tab changes that do not stop the navigation entry's lifecycle. Home
attendance and Employees retain loaded state and read-only cache/access
observation in their navigation ViewModels. Hiding either tab cancels acquisition
and user actions, without clearing its content or starting another request on
return. An unfinished/failed request or invalidated read requires acquisition;
manual refresh and mutations remain explicit triggers. Admission changes still
invalidate hidden content. Clearing the route owner cancels its observations;
no hidden tab polls the network or retains a view/controller callback. The
Home capture and monthly history have separate ViewModels and encrypted cache
windows. History observes attendance and refreshes a selected month; it owns no
permission, location or capture actions. Inbox and Employees also have separate standalone
routes with owners distinct from the tab entries. The service catalog contains
business workflows; Inbox has its own tab and Employees is reached through Chat/Contacts. Personal
profile access belongs to Account and has its own admission, separate from menu
visibility.

Employee search uses a separate navigation entry with its own directory ViewModel
and list state. It reuses directory acquisition and rendering contracts without
sharing a ViewModel with the browsing tab. Back disposes search work while the
tab retains its admitted page, filter and scroll. Company changes dispose both
owners before any incoming company content is shown.

The root navigation graph belongs to the session and workspace IDs. Logout, a
replacement session, or a company change
disposes its protected routes and saved tab state immediately; these boundaries
never animate an outgoing account's content. Token rotation does not change that
identity. Page transitions inside a session use a short fade. `AppTabContent`
retains each finite tab's rendering state and cancels superseded transitions.
`ScopedNavHost` retains its controller across configuration recreation and gives
each scope a distinct root graph. Navigation then clears the previous entry
ViewModelStores and saved back stacks; replacing a composable alone would leave
those stores attached to the Activity/window owner.
The application retains that controller above temporary lock/availability gates,
so reopening a gate reuses the existing route owners instead of allocating an
additional set. Session-aware use cases still own admission and stale-result
checks independently of whether a screen is currently visible.

Read changing presentation state inside a composable navigation entry. A retained
graph must not depend on rebuilding its builder to replace a captured snapshot.
Feature entries normally collect their route-owned ViewModel; app-owned gates pass
a Compose `State` holder and read its value inside the entry. Bootstrap recovery
tests keep the same route while a pending read fails and an explicit retry succeeds.

The app host handles top/side safe areas and keyboard insets. `AppTopBar` is rendered
in the page's fixed `topBar` slot, before its independently scrolling content.
Content padding does not move the top bar. AppPage handles the bottom
safe area for standalone pages; dashboard's bottom navigation owns that inset instead,
and its content marks the inset consumed. The bar draws its surface beneath the system
navigation area. On wider windows a side rail/sidebar replaces the bottom bar and
pages own their bottom safe area. Moving the content composition preserves its
rendering state across that layout change. Android window icon/contrast configuration stays in the platform runner.

## Checks

The architecture script checks module direction, cycles, dependency declarations, selected
forbidden imports, and presentation I/O/state ownership. It is a source-level guardrail,
not a full Kotlin semantic analyzer. Review constructor dependencies and orchestration
when introducing new code. Compiler, behavioral tests, and UI smoke tests complement it.

## Durable quoted-reply pipeline

[Quoted draft/outbox identity](collaboration-chat-quoted-draft.md) adds an optional
original event reference to encrypted local intent and exact native content identity.
Background recovery carries the same reference. It does not fetch the target or
store quoted body snapshots. Quote UI/history and actual native acceptance remain
pending; final pipeline checks are recorded in the linked evidence.
