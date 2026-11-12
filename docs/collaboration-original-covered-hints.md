# Covered hints for Original conversations

An adopted LEGACY conversation keeps incoming history in its original Company and
device partition. Its published Space conversation and current device can have different
IDs. Looking only in Space history incorrectly queues a new acquisition for a head that
is already retained and fully resolved in Company history.

`QueueCollaborationSpaceIncomingRoom` now accepts the explicit Space owner and resolves
the original coordinates from the immutable room binding and current registered device.
The use case owns repository reads and admission fences; pure domain policies validate
the captured source and pending-history facts. Repositories and datasources retain their
existing responsibilities.

Coverage requires the exact event, original Company/device, provider EOF, a resolved
reference, an empty bounded pending page, no active pending pass and unchanged progress.
The registration and local account/admission are checked around each read. A clean hint
returns without a write, wake or scheduler request. A newer queued hint and unrelated
work remain intact. Missing registration, unknown head or unfinished history follows the
normal Latest alias route; it cannot suppress work or copy Company facts into Space.

Published Space history retains its existing coverage rules. Ordinary credential age
does not hide owned local history; a confirmed denial or replaced account remains gated.
No SDK opening, network call, schema, worker limit or retry timer is added by this check.

Thirteen new Original regressions cover clean and unfinished history, malformed or
changed facts, registration/account replacement, denial, cancellation, storage failure
and independent queued work. Existing Published test identities remain unchanged. The shared fixture keeps its original
LEGACY default; Published coverage tests select SPACE explicitly, preserving the other routing
fixtures and their assertions.
The native worker proof retains the original four-turn bound, first-page content,
empty-repeat and covered-hint no-ops, and offline reopening in a new process.

Local validation is complete:14,581 accepted cases (12,543 fresh and2,038 verified against
unchanged dependencies), plus the seven native scenarios on the identical APK. The
[full measurement](measurements/collaboration-original-covered-hints-2026-11-12.json) records
source, case, report and artifact hashes. The shared-fixture correction changed only test
inputs after native execution; the APK and production inputs remained identical.

Next is the [late-key receipt proof](collaboration-native-late-key-plan.md). This validation
does not close the complete chat acceptance group or update the delivered SDK6 phone build.
