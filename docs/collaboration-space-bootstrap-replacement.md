# Explicit organization bootstrap replacement

Transport validation is accepted. Focused46 passes123.870s. The first complete run
passes5,329 tests but fails formatCheck on one test file. Its whitespace-only correction
leaves4,217 other inputs identical. Final root/Android/browser/lint/format/architecture
and strict dependency checks pass207.192s with4,218 unchanged inputs:677 fresh browser
cases and4,652 matching retained task reports, all without failures/errors/skips.
[Evidence](measurements/collaboration-space-bootstrap-replacement-2026-11-06.json).
The owning durable workflow and product activation are not implemented by this change.

`CollaborationSpaceSetupRepository.replaceBootstrap` sends one original/replacement
pair to `native-device/bootstrap/replacements`. The caller must persist both intents
before transport. The new operation uses the actual installed build/platform and the
current same-parent native bearer. Historical original build metadata stays in the
request body. It never overrides headers or enrolls another SDK identity.

The request mapper verifies account/space/native-parent binding, immutable provider
USER/DEVICE, supported revisions and the monotonic gateway relation. The raw datasource
performs one API call; the repository owns mapping and the existing safe failure boundary.
Ordinary retry/bootstrap keep their original wire formats and idempotency inputs.

The bounded 4 KiB response must contain the exact full superseded descriptor, the new
operation ID and the matching gateway tuple. A UUID alone cannot prove supersession.
Changed descriptor fields, provider identity, versions or credential namespace fail
mapping before publication. Credential dates remain typed facts; current hard expiry,
admission and outcome persistence belong to the owning use case.

The server design relies on its undeletable, strictly increasing gateway head. A
replacement that commits beyond the original expectation fences late original
application/replay. The result does not claim whether the original once applied.
Server races, transaction rollback and actual API acceptance require their own gate.

New transport cases cover unchanged original payload across native renewal, actual
iOS headers, every proof-field mismatch, foreign/future input, denial classification,
response size and cancelled late results. They pass alongside existing setup owning-flow regressions.
Architecture checks pass for74 modules, with browser and assembled application validation
above. Physical device and actual Apple acceptance are not asserted by this transport change.

Next: finish backend supersession acceptance, then retain the linked
original/replacement intents through atomic local preparation, lost responses and
process interruption. No existing Unconfirmed command may be erased or silently
rewritten. Original SDK-store possession, parent recovery and runtime handover remain
separate requirements before organization chat can be activated.


## First-source CI audit — 6 November 2026

Program 3d666135 first control 42277469255 and native 42277469245 both completed
successfully. Artifact digests match GitHub REST: 4,315 portable and 1,679 Apple tests,
395 tests on each Android ABI, no failures/errors/skips. The actual iOS host built.
Each Android ABI retains both encrypted native recovery scenarios, worker scenarios
and notification cleanup. The native workflow pins Platform 62ee1181. [CI evidence](measurements/collaboration-space-bootstrap-transport-ci-2026-11-06.json)
records exact commits, artifact checksums and proof limits. No redispatch, SDK
publication or local accepted-send rerun was used to reconcile this evidence.
