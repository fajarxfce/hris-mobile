# Sharing a published background runtime

Native81 retained its account and encrypted store but made 154 requests in about
162 seconds. Initial Home never became quiet: device synchronization repeatedly
opened a client while incoming recovery failed with `Unavailable`. The data
registry rejected every second background borrower, including when the existing
native client had already been published. Waiting for acquisition in the domain
use case did not resolve this separate restriction during the first lease's lifetime.

`SdkRuntimeRegistry` now admits a second finite reference to the same available,
published client under the existing owner, device and physical-store identity.
Its global limit remains two background references and five client reservations.
Unpublished construction, unavailable credentials, history reopening, ownership
changes and handover remain fenced. The domain still owns admission and its deadline;
the registry only coordinates native resource ownership.

Closing either reference leaves the other usable. Closing the final reference
retires a background-only client; a promoted foreground client keeps its existing
owner. Company denial invalidates both references and requests one native stop.
The existing drain owner continues handling cleanup outside the registry monitor.

Three regressions fail against the previous registry: both release orders,
competing joins at the reference limit, and revocation of a shared client. The
focused validation passes all 661 tests: 595 Android host tests and 66 company,
incoming and recovery-worker integration cases. Format, architecture and detekt
also pass on unchanged inputs. Full SDK6 validation passes 5,476 fresh tests
(5,326 desktop and 150 browser); the 595 Android host results remain current from
the focused run. Root check, release/proof compilation, lint, Wasm/iOS Arm64 source
compilation and quality checks pass with all application/library inputs unchanged.
Native82 must repeat the initial Home scenario, retained-message/history checks,
lifecycle and credential renewal
before phone installation. Native81 remains rejected; its failure is preserved in
[the candidate record](measurements/collaboration-release81-candidate-2026-11-09.json).

## Publication under an already selected application owner

The selected application owner and a finite recovery lane can reach one physical
client in either order. Foreground entry may encounter construction already in
progress, or a fresh selection can arrive after the background client is published.
Previously both paths could leave the available client background-only: releasing
its final lane reference stopped it despite the already admitted application owner.
Two deterministic regressions reproduce that lifetime gap. They do not establish
that this was the precise unrecorded activation failure in native84; native85's
instrumented observation completed activation and retained healthy sync.

The registry now transfers the available published resource to the matching fresh
foreground owner at publication or a fresh retention update. It consumes the domain's
existing admission decision; it performs no permission calculation, SDK opening,
synchronization or retry. Other-company resources still close with their final lane.
The expired-admission retention marker can preserve a previous foreground owner but
cannot promote an independent background resource. Failed publication, cancellation
before publication, parent loss, company denial, capacity and pending drains retain
the existing ownership rules. No caller receives another owner's native close handle.

All31 registry cases pass, including the two ordering regressions and new expired/
failed publication checks. The focused run passes620 cases (599 Android host and21
application lifecycle); the full check passes693 fresh cases with the current Android
results reused. Android compilation/lint, Wasm/iOS source and quality checks pass.
[Exact validation](measurements/collaboration-selected-runtime-publication-2026-11-09.json).
Native86 must still pass retained-state, healthy sync, lifecycle and renewal gates.
This HRIS runtime ownership change is not a generic Starter or inspector policy.
