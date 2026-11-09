# Organization readiness from the original native client

An adopted organization runtime keeps its original physical Matrix identity. It now
subscribes to the organization V1 readiness type using the logical owner, device and
bootstrap operation captured from its installation reservation. The expected Matrix
sender comes from the original SDK record. Packet contents cannot choose the source
runtime, native parent or organization, and no Matrix user is derived from those IDs.

AndroidSdkReadinessRegistration owns one TaskHandle lifecycle for both protocols.
Its private constructor is exposed through explicit company and organization factories;
each chooses a fixed event type and strict mapping. The existing company V2 behavior
is preserved. Both accept only the client's own clear control, a body up to4KiB and
exactly nine string fields. The organization factory additionally applies the pure
logical-source/scope policy. Malformed, foreign, encrypted or wrong-protocol packets
are dropped before they reach the bounded signal stream.

CollaborationSpaceReadinessSignal carries the immutable logical runtime source and
the parsed control. The repository exposes its own32-item stream with oldest-item
drop on overflow, separate from company readiness. It does not request recovery,
acknowledge a receipt, open another SDK or start sync. Platforms without a native
source retain the empty default stream; this is not an iOS native callback implementation.

Registration precedes restore and shares the existing acquisition/shutdown owner.
A close immediately deactivates callbacks, waits for in-flight acquisition, releases
a late returned handle, then closes the native client and private directory. Failed
handle cancellation still attempts handle close. Callback predicates only read the
lease's atomic stop state; they do not take the registry lock while holding the
registration monitor. Registry retirement already calls deactivation in the opposite
order. Exact current-parent/runtime checks belong to the later consuming domain flow.

Four new pure cases cover trusted-source matching, changed local/native parents,
malformed installed operations and wrong account/organization/device controls.
Five new Android cases cover original physical sender versus logical source, strict
frame rejection, obsolete callbacks, group-only subscription on the real lease with
inert FFI handles, and close during subscription while acquisition still owns the
directory. Existing company registration,lifecycle,registry,adoption and original
queue tests also run. Focused validation passes84 cases in198.331 seconds,zero
failures/errors/skips,format/architecture/detekt and4,584 unchanged source inputs.
Every source,Inspector input and fresh XML is checked. The full required gate passes
4,297 fresh cases in630.629 seconds:3,645 desktop,626 Android host and26 forced
browser,zero failures/errors/skips. Root check,Android release compilation/lint,
shared Wasm/iOS source compilation and quality checks pass. Focused/full manifests
match exactly. [Retained evidence](measurements/collaboration-space-readiness-listener-2026-11-09.json).

These are source and inert-FFI lifetime checks,not provider delivery measurements.
Actual foreground sync,wake persistence,group recovery processing and product routing
remain required. The group runtime is restored paused;its admitted owning flow must
start the same client's sync before controls can be received. The company-only
recovery worker cannot acknowledge group work. Bounded signals accelerate recovery;
startup/reconnect must independently discover retained originals after missed signals.
No new SDK,APK,phone or live rollout is included.
