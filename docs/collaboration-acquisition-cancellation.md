# Cancellation of obsolete native acquisition

The recovered build71 Crashlytics event points to the original repository's
`registry.attach` rejection. Native construction had completed after that reservation
was retired. The source threw IllegalStateException and the storage boundary displayed
it as a local data error. The report identifies the failed publication fence; it does
not identify which concurrent ownership decision retired that historical reservation.
[Exact sanitized evidence](measurements/collaboration-storage71-crashlytics-2026-11-09.json).

Expected owner retirement now cancels the obsolete acquisition. Checks before native
construction, after restore, during adopted-record publication and at lease completion
preserve this meaning. Genuine storage failures and broken registry invariants retain
their existing error handling. The original reservation remains occupied until the
native client, credential owner and directory have finished their existing drain.
Neither private keys nor pending message identities are replaced.

The Android repository consumes a raw SDK datasource contract. The existing native
implementation performs the same SDK operations; suspended test implementations can
exercise late construction and restoration through the actual repository. Authentication
registration owns creating, deactivating and releasing its own delegate/handle, including
failed attachment. Cold and live credential status share one reservation-fenced publisher
in the repository. No policy moves into a ViewModel or diagnostic adapter.

Application composition joins the old activation before replacing it. Otherwise the
new invocation can observe the still-finishing old domain run and finish without doing
its own work. A fresh admitted publication can resume a cancelled acquisition for the
same owner, preserving that owner's credential observer and its rejection history.
Completed activations and ordinary admission renewal do not create another attempt or
a delivery wake. Domain admission, durable original commands and credential-recovery
policy continue to decide whether work is permitted.

The focused gate passes605 fresh Android-host/application tests plus formatting,
architecture and detekt checks. The two original repository regressions and two
application ordering/readmission regressions failed against their previous behavior.
The full combined gate remains pending. [Validation record](measurements/collaboration-acquisition-cancellation-2026-11-09.json).
Native recovery of the retained78 original and the exact release79 installation remain
separate required acceptance; neither has been performed for this candidate.
