# Organization readiness protocol and encrypted receipts

Organization readiness uses its own V1 control type,
`dev.fajar.hris.space_recipient_ready`. Exactly nine bounded string fields identify
the publication, organization, account, native session and device, plus its positive
commit revision and publication/expiry times. IDs are canonical nonzero UUIDs.
Times and revisions use canonical decimal strings; the publication has an exact
24-hour wire lifetime. No local clock or packet grants permission to read or send.
The existing company V2 control remains a different protocol.

`CollaborationSpaceReadinessReceiptRepository` retains one encrypted checkpoint per
account, audience, native session, organization and device. The local session ID is
an ownership guard, so a same-family local session replacement can reopen the exact
facts. Organization and company ciphertext have separate partitions and authenticated
labels even when all IDs match. The account-owned content key and neutral monotone
progress model are shared; neither repository calls the other.

The checkpoint stores requested and pending revisions as strings, preserving signed
64-bit precision in browser storage. Exact generation compare-and-set prevents an
old completion from replacing newer work. Only the owning flow will decide retention,
scheduling and acknowledgement policy. Acquisition and mapping share safeStorageCall;
cancellation propagates. Lost keys cannot be regenerated over retained inactive facts.
There is no event-ID history, provider call, SDK creation or polling loop here.

Four pure protocol cases check scope separation, malformed/bounded input, precision
above 2^53 through Long.MAX_VALUE and exact time encoding. Eight shared storage cases
exercise reopen, concurrent first writers, late writes, simulated cancellation after
an actual commit, account replacement, altered authenticated metadata, key loss and
bidirectional ciphertext transplants between company/organization partitions. Concrete
bindings use desktop SQLite, browser IndexedDB and Apple Room SQLite. The application
binding test also resolves the new singleton through the production DI graph.

Focused Linux validation passes24 cases in244.810 seconds:4 new+3 company protocol,
8 new+7 company storage,and2 application binding cases. Formatting,architecture and
detekt pass. All4,582 source inputs and the Inspector inputs remain unchanged;each
fresh XML and source hash is audited. The full required gate passes4,284 fresh cases in588.489 seconds:3,641 desktop,
621 Android host and22 browser,zero failures/errors/skips. Root check,Android
release compilation/lint,shared Wasm/iOS source compilation and quality checks pass.
The focused and full manifests match exactly.
[Retained evidence](measurements/collaboration-space-readiness-receipts-2026-11-09.json).
Apple runtime execution is not implied by a test source or Linux metadata compilation.

Next connect exact native-client provenance and current logical runtime guards,then
actual durable organization wake processing. The existing company-only recovery
sweep cannot by itself acknowledge an organization signal. Parser acceptance and
receipt persistence are not proof of device wake or message delivery. Native callback,
background recovery,product routing and phone rollout remain separate work.

Hosted run [42697887863](https://github.com/fajarxfce/hris-mobile/actions/runs/42697887863)
also passes on immutable `da2481732e51132fac7249fc31d732f037c2222c`: 9,173 cases,
comprising 6,662 portable/Android-host/browser and 2,511 Apple simulator cases, with
zero failures, errors or skips. The unsigned iOS application host builds successfully.
Artifact SHA256, GitHub digests, ZIP CRCs, every XML report/count and the complete
previous report set plus the new protocol/storage cases are verified. Apple includes
eight actual Room receipt cases and four protocol cases. This evidence predates the
native-listener, wake and local-inventory successors; it does not validate their bytes
or implement native Apple Matrix callbacks.
[Hosted evidence](measurements/collaboration-space-readiness-protocol-hosted-2026-11-09.json).
