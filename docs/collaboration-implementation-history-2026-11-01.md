# Collaboration checkpoint history — 1 November 2026

## Active collaboration implementation — 1 November 2026

Control-denial checkpoint Mobile `92d91275` is now verified in hosted CI:
control `41258471231` passes all 26 steps (201 desktop, 177 browser, 177 iOS
simulator); HRIS `41258471223` passes all 17 steps with an actual SDK run in
64.046s, process restart, retained-cursor recovery and completed cleanup.
The recorded 131 Mobile/24 Platform hashes match the immutable checkouts;
[evidence](measurements/collaboration-control-denial-2026-11-01.json) retains the
reports. This is separate from the newer owned-summary/cache work.

Mobile now consumes the owned summary page/detail contract at Platform
`8813ea9eee28cafc274eafcd41456340f132ae2f`; CI pins that backend.
[Evidence](measurements/collaboration-mobile-summaries-2026-11-01.json): one new
domain/four data cases, focused 61s, full root/browser/Android/Wasm/lint 232s,
2,208 desktop cases, 97 domain/85 data browser, zero lint errors. Both summary
paths share current-owner/denial guards, bounded decoding and exact counterpart
validation. No UI, live service or phone change; encrypted owned projections are next.

Transport checkpoint `4d90c6e0` is fully verified in CI: control `41257419472`
passes all 26 steps (187 desktop, 163 browser, 163 iOS simulator); protocol
`41257419463` passes all 40 steps (classic 73.011s, native 86.187s); HRIS
`41257419507` passes all 17 steps and actual SDK recovery in 72.015s. All recorded
24 Platform/131 Mobile source hashes match the pinned checkouts; cleanup completes.
See [recovery evidence](measurements/collaboration-sync-recovery-2026-11-01.json).
Apple native chat interoperability remains pending despite the common Apple checks.

Control preparation, replay, receipt reconciliation and runtime install/restore
now preserve denial through failed post-request owner reads. Explicit guards take
the preceding result. A failed uncertainty write cannot erase the retained denial
or start another HTTP attempt. Owned credential denial also survives failed local
admission and failed resource cleanup; partial/late leases still close.
[Verification](measurements/collaboration-control-denial-2026-11-01.json): ten new
common cases; preliminary focused checks 45s, final root/browser/Android/Wasm/lint
223s, 2,203 desktop cases, 96 domain browser, zero lint errors. No native transport
change or local SDK rerun; earlier SDK proof remains tied to `4d90c6e0`/`013f8092`.

Setup and all four conversation discovery reads now retain received server
authorization denials if the following session read fails. A newer authorization
denial still wins. [Evidence](measurements/collaboration-read-denial-2026-11-01.json)
records four new common cases, focused verification in 43s and full root/browser/
Android/Wasm/lint in 202s (2,193 desktop, 86 domain browser, zero lint errors).
No provider transport changed; native SDK evidence below remains at `4d90c6e0`.

[Evidence](measurements/collaboration-sync-recovery-2026-11-01.json) · [Contract](collaboration-sdk-lifecycle.md)

The actual HRIS CI fixture now pins Platform `013f8092114eef7a603930bdf242aed859ccba7c`,
which includes the required direct-metadata contract.

Common admitted conversation entry and retained-cursor sync recovery are implemented.
Entry verifies the active device, owned DM, READY room and current owner after each
request; a late result closes only its child. Structured SDK denials survive failed
post-request reads and disposal. The pinned FFI loop stops on its first error;
the replacement owns one `syncOnceV2` request, bounded backoff and the SDK's cursor.
Final denied responses remain visible even when cleanup fails. The gateway also
confirms the SDK's post-join `m.direct` only when it matches the existing HRIS
DIRECT scope. No provider metadata writes or arbitrary account-data are enabled. The server fixture
injects a sync 429 only after actual process death and requires a successful retry
with that same cursor, both encrypted replies and the original queued transaction.

Twenty-nine new common cases pass. Final full root/browser/Android/Wasm/lint passes
in 240s: 2,189 desktop cases, 82 domain/81 data browser,
zero lint errors. Later proof-only corrections each rerun root/proof assembly/lint
(66s diagnostic, 64s current owner, 65s bounded explicit entry attempts). Actual
SDK run `c17b1b10` passes in 39.126s (66s Gradle/quality,
one API and 32 driver cases), with cleanup complete. Backend regression passes
in 411s with 40 domain, 204 data and 20 HTTP
cases; focused verification passes in 54s. Format and architecture checks pass. All 24 Platform/131 Mobile
hashes match before/after. Proof APK `8a212769`; dev APK `87631d85` is built,
not installed. Emulator stopped; no live/schema/phone changes.

The earlier missing-room failures were a fixture ownership bug: restoration changed
the local session, but entry retained its old owner. RuntimeNotOpen correctly rejected
it before SDK join. The initial room-ID hypothesis was incorrect; all attempts are
retained. A later 503 provider-busy join is also retained. The fixture permits at most
four explicit idempotent entry attempts; product repositories still issue each join
once, with no automatic enrollment. The deterministic sync fault remains independent. Two subsequent failed runs
exposed the SDK's post-join metadata confirmation. Both failures are retained;
three new domain, three database and two HTTP cases verify the correction,
including malformed requests, identity isolation and permission loss.

**Continue P0–P8; none of the 54 groups is accepted.** Build encrypted owned
conversation-list projections and app entry with explicit shared runtime ownership,
then product history windows, offline message projections, bootstrap/cross-family
recovery, logical-ID reconciliation and chat UI. Treat summary UUID continuation
as live page navigation; it gives neither snapshot completeness nor change order.
Keep native SDK keys/stores intact. The current 512-item observer cap is a guard,
not a product history window. Keep HRIS adapters in Mobile/Platform and port only
verified reusable foundations to Starter.

Predecessor `0ef8018c` control CI `41250076314` passes all 26 steps:
158 desktop, 134 browser, 134 iOS simulator cases; 120 source hashes match the
immutable commit. Provider CI `41250076315` passes both modes/all steps, classic
90.228s/native 85.289s, with 14 matching Platform hashes per mode. HRIS CI
`41250076331` failed after provider-capacity sync 429s; its timeout/cleanup evidence
and the pinned SDK diagnosis are retained. Earlier command CI `39217be2` HRIS
`41248280306` also passes all 17 steps: 68.983s, cleanup complete, 108 Mobile/15
Platform hashes verified. Apple native chat interoperability remains pending.

