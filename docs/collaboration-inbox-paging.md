# Retained local inbox paging

The Group inbox presentation uses AndroidX Paging 3.5.3 across Android, desktop, Apple and Wasm.
The retained account/Group ViewModel owns one Pager and admitted local version observation.
PagingSource calls the admitted finite window use case; it never acquires network/native data.
One generation holds one index version, caps reads at 50 rows, restores by visible conversation
identity and maps exact older/newer cursors. Equal or lagging metadata stays quiet. A Changed
result waits for a real publication or disposal rather than recreating itself on unchanged input.
Failures stay explicit; admission failure hides private rows and cancels visible contact reads.

The ViewModel owns presentation state and typed events, using existing admitted use cases.
Visible contact readers are bounded at 50; retained names are bounded at 200 loaded conversations.
Stopping the tab releases those readers without clearing known names. Partial label results
preserve names, explicit absence clears one, and denial clears all. Clearing the route cancels
Paging, version/label observations and effect delivery. The navigation entry receives an explicit
account/Group store, reuses bounded warm thread preparation and collects with lifecycle ownership.

The page reuses AppLazyPage and ChatPersonRow. It retains populated rows during a replacement
read, has no extra appbar, and leaves hub tabs/FAB to its caller. The row gives independent draft
text/date priority, otherwise shows the actual newest preview including pending and failed messages.
Acknowledgement changes only the outgoing icon; it does not determine preview order or time.
Sent means one server check, not an invented delivery/read receipt. Media labels are localized.
A failed continuation keeps loaded rows and exposes retry only until that local read succeeds.
A successful explicit retry clears its obsolete failed-read marker; equal metadata stays quiet.

Validation includes 25 shared Paging/ViewModel tests, 14 desktop UI tests, existing real DI
resolution/disposal, retained data during suspended refresh, exhaustion after composition reopen,
visible identity/offset retention after insertion, cancellation, denial, status semantics and
light/large-text dark/landscape screenshots. Screenshot files were separately inspected.
The initial 37 presentation cases passed, but four existing desktop fixtures used the removed
BackGestureDispatcher API. They now drive NavigationEventDispatcher through the actual Compose
compatibility owner, with explicit disposal. All 97 existing application/navigation/inspector
cases remain intact. Initial transparent screenshot canvases were corrected in the test host.
All 17684 prior executed test identities remain and were freshly executed on the final source.
The first full attempt passed 10,736 cases, then stopped before browser tests because the Web
package lock still referenced the prior Skiko workspaces. The normal Gradle upgrade changes
only the two local Skiko links from 0.9.22-2 to 0.9.37-4; external npm packages and integrity values
are unchanged. This is the sole manifest delta after focused acceptance; the final full run
executes every prior case again. The failed full attempt is retained separately.
Deprecated test-dependency accessors were replaced with the same verified catalog coordinate
before the accepted focused/full runs. The application test host explicitly owns its lifecycle
and ViewModelStore: Compose 1.10 now supplies an outer owner that outlives the fixture scope.
Three original disposal assertions exposed that fixture mismatch; they remain unchanged. Apple test sources compile locally; no new Apple
runtime, phone latency, authenticated emulator flow or Group rollout is claimed here.

The feature entry is not yet connected to the app root. Complete Group hub/contact ownership,
incoming runtime activation and measured app navigation before live enablement or phone delivery.
The generic Compose/Lifecycle alignment needs an independently verified Starter port after Mobile acceptance. The HRIS presentation stays in Mobile. All 54 communications PRD groups stay open.

Evidence: `docs/measurements/collaboration-inbox-paging-2026-11-14.json`.
