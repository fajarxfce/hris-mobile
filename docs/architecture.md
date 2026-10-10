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
Identity is shared core domain because both auth and dashboard consume it. Features do
not consume each other's ViewModels, data modules, or repository implementations.

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

Sign-in drops duplicate submissions and freezes credential edits until completion.
Clearing its ViewModel cancels the request. The repository checks cancellation before
publishing a successful session. Dashboard refresh cancels the previous request and
retains existing data when a later refresh fails.

The preference port already serves as a datasource contract. Onboarding does not introduce
a forwarding datasource around it. The in-memory session datasource owns a replayed DTO
snapshot; it does not own UI state or decide when the application should sign in.

## Navigation

Features own their serializable route and NavGraphBuilder registration. The app composes
their entries and reacts to onboarding/session state. Authentication transitions replace
the root stack, so sign-out removes protected screens. Dashboard owns its three tab
states; tab selection does not create independent navigation histories in this starter.

The app host handles top/side safe areas and keyboard insets. AppPage handles the bottom
safe area for standalone pages; dashboard's bottom navigation owns that inset instead,
and its content marks the inset consumed. The bar draws its surface beneath the system
navigation area. Android window icon/contrast configuration stays in the platform runner.

## Checks

The architecture script checks module direction, cycles, dependency declarations, selected
forbidden imports, and presentation I/O/state ownership. It is a source-level guardrail,
not a full Kotlin semantic analyzer. Review constructor dependencies and orchestration
when introducing new code. Compiler, behavioral tests, and UI smoke tests complement it.
