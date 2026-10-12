# Architecture

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
A transport/storage error cannot replace a known access denial or expired read
lease and make retained cache data readable. Presentation stops that cache
subscription until a fresh admitted acquisition succeeds. Cache deletion remains
best effort; tests retain old ciphertext when cleanup fails to verify the gate.

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
Workers are platform application entry points that invoke `SyncTask` use cases.
The [workspace admission](workspace.md) use cases own offline eligibility and live
revalidation. Its repository coordinates encrypted storage and raw remote access;
foreground lifetime and the protected-route failure gate belong to application MVI.
See [native identity](native-identity.md) for the active HRIS integration and
[inbox](inbox.md) for the first encrypted HRIS feed integration and
[sync](sync.md) for the inherited worker and command contracts.

The [work calendar and attendance](workforce.md) follow the same ownership
boundaries with bounded encrypted snapshots. Acquisition and admission belong to
use cases; repositories map transport/storage data and atomically replace snapshots.
Attendance capture coordinates the generic [location repository](location.md),
current workspace and durable command storage. Permission requests, evidence
freshness and offline admission remain attendance policy. The location datasource
only performs the requested OS operation; it neither opens Settings automatically
nor starts a long-lived tracking subscription.

The [Leave page](leave.md) uses its own minimal self-service employment directory,
independent of People and Workforce availability. Its use cases admit the selected
company, acquire scoped balances/history and atomically publish one encrypted
overview. The ViewModel owns filter selection, read gates and cancellation; it
never recalculates accounting balances or sends technical API types to the page.
Own-request details have separate acquisition/observation use cases and an
independent bounded encrypted partition. The shared feature cache repository
implements both read contracts; a codec only handles the chunked storage format.
History pagination changes the visible revision page, not current request facts.

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
derived data. Features own encryption, command admission, replay and conflict policy;
the database only enforces ownership, immutability, bounds and atomic revision checks.

Large file contents use the separate [blob store](blob-storage.md), with bounded
opaque blocks, transactional progress and account-owned reservations. File adapters
own encryption and mapping; use cases own import, transfer and cleanup policy.
The [file input adapter](file-input.md) owns OS pickers and transient readers;
those resources never enter a domain contract or a ViewModel.

## Navigation

Features own their serializable route and NavGraphBuilder registration. The app composes
their entries and reacts to onboarding/session state. Authentication transitions replace
the root stack, so sign-out removes protected screens. Home owns its
Home/Inbox/Employees/Account tab state; changing tabs does not create independent
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
business workflows; Inbox and Employees are reached through their tabs. Personal
profile access belongs to Account and has its own admission, separate from menu
visibility.

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
