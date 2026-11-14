# Independent retained navigation stacks

rememberScopedNavigationState accepts an optional stable ownerKey. Two concurrently retained
stacks under the same window use different keys, so their NavigationViewModels stores cannot be
shared accidentally. The controller and saveable navigation state have that same composition
identity. Existing callers retain their default ViewModel key. Account, company and admission
changes continue through ScopedNavHost scope, not new keys retaining obsolete owners.

Three actual Compose navigation tests mount two controllers with the same route types under one
window. Changing the Company scope clears only its old detail and leaves Group and window models
alive. Recreating composition retains both stacks, counters and route models. A fresh window
restores the admitted Group detail and counter while discarding the obsolete Company stack.
Final teardown unmounts composition before destroying the parent and clears every model exactly
once. Existing application-flow and restoration assertions are preserved.

The first99-case run passed98 and exposed a fresh-window fixture defect; an8-case diagnostic run
showed the route restored with its counter reset. Exact Compose1.10.5 saveable sources confirm
that saveAll returns the holder's mutable backing map. Navigation2.9.1 removes retired entry state
from that map in onCleared. Holding that map across simulated destruction lets cleanup erase the
fixture's supposed process snapshot. A third8-case run rejected MutableIntState until its scalar
value was explicitly copied as well. The fixture now copies its supported saved values before
destroying the old window, matching the OS snapshot boundary. No production workaround, relaxed
lifecycle check or weaker restoration assertion was added. The final8-case focused run covers all
new cases and the5 original restoration cases; full checks include the entire application flow.

The Group product routes remain inactive. Port the two generic files to Starter with its own
saved-state format1 (Mobile retains format3). The prepared Starter workspace is
.work/navigation-stack-identity-port-2026-11-14; refresh it from this exact accepted source before
applying. Then resume Group integration with AppViewModel owned by the window. New-contact entry
still needs a peer-addressed durable draft/original queue and canonical handoff; do not treat peer
or setup-command IDs as conversation IDs. Complete company filtering remains unresolved.

No new APK, phone interaction, live deployment or Apple runtime execution is claimed. Phone92,
GroupOFF and undeployed V117–140 remain. All54 communications PRD acceptance groups remain OPEN.

[Exact validation](measurements/navigation-stack-identity-2026-11-14.json).
