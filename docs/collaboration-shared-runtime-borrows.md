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
also pass on unchanged inputs. Full release validation remains in progress. Native82 must repeat the initial
Home scenario, retained-message/history checks, lifecycle and credential renewal
before phone installation. Native81 remains rejected; its failure is preserved in
[the candidate record](measurements/collaboration-release81-candidate-2026-11-09.json).
