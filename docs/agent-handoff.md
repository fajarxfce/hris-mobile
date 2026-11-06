# Agent continuation — HRIS Mobile

This is the active checkpoint. The [historical handoff](agent-handoff-history-through-build50.md)
retains earlier work verbatim; its old “current” headings are historical, not instructions
to restart accepted builds or devices. Detailed acceptance remains linked below.

## Current work — 6 November 2026

The original report that phone messages remain pending is **OPEN**. Build50's passive
log exposed three `QueueMappingNativeFamily` failures while all80 traced HTTP calls
succeeded(max460ms). This identified account-owned older-device work blocking the
current physical queue; it does not establish every original message's outcome.
[Physical evidence](measurements/collaboration-phone-queue-family-2026-11-06.json).

The correction is committed/pushed as **68d848b7fed810984ddb59ca53079442dcac7819** on
`feat/communications`. Mapping/staging retain other stores' original state, revisions,
keys and transaction IDs. A pure domain policy selects current-store work before the
worker's eight-command limit. Unknown current echoes, captured foreign transactions,
same-store uncertainty and ownership guards remain enforced. No ViewModel workflow
or Starter port is involved. [Contract](collaboration-chat-queue-ownership.md).

Seven new regression cases produced five assertion failures on unchanged production
source. Identical tests passed with four production changes:76 focused cases/143.266s.
Full57507 PASSED540.556s:5,454 fresh cases/639 suites, zero failures/errors/skips,
4,254 unchanged inputs and zero Android lint/Detekt findings. The real encrypted
AppContainer retains ten old uncertain records through restart while only the new
message confirms; its provider is simulated. Old-message delivery remains unproved.
[Evidence](measurements/collaboration-chat-queue-ownership-2026-11-06.json).

**Release51** from exact68d848b7 is accepted and installed on the phone. Install67886
confirmed in77.004s at23:22:17UTC; exact device APK bytes, UID10962 and original
first-install time2026-10-29 15:48:23 are preserved. No uninstall/data clear/UI launch. ARM64 devRelease build/lint passed252.494s,
nondebuggable APK121,006,372bytes SHA256
`61f569404b9a6fefe426b7c632aafcbb1a75629894a693992a4ee3a9040857b0`.
Existing development signer, Firebase/dev API, source revision, backup exclusions,
16KiB alignment and exact published SDK4 native bytes were verified. The exact APK
upgraded the original Bima emulator50→51 in place; two offline/two online room entries
show12 original rows without recovery/older/reconnect prompts, stable online30s, zero
new sends. Offline clock+3900s tested ordinary expired access; clock/network/ADB
privilege were restored. Both private screenshots were viewed. Owned emulator stopped
23:20:54UTC; original userdata and all sidecars remain. Private drivers/results:
`.work/release-phone-2026-11-06-build51`. Phone preflight verified installed50 and its
signer/hash; installation uses explicit10.77.77.3:5555 and `--install-only`.

The ONLY heavy local job is **8440**, existing-store handover **proof-build-2**, under
`.work/space-store-inventory-2026-11-06/closed-handover-next`. Ten Kotlin files are
UNCOMMITTED: seven product/host-test files plus three native-proof fixture files.
Focused86050 PASSED34 cases in127.354s, zero failures/errors/skips,4,266 stable inputs.
The first proof build72231 assembled its APK but FAILED Detekt: instrumentation
onStart grew to104 lines against100 allowed. Original SARIF is retained; scenario
execution is now separated from instrumentation lifecycle. The retry checks APK,
formatting, architecture and Detekt. No emulator is active; keep source frozen.
Actual SDK4 two-ABI/process-death proof is prepared under `native-1`, not yet run.
It uses a fresh owned5564 emulator, never the phone or original Bima data. No owning
use case, server freeze, product activation or full-candidate acceptance exists yet.

The ten separate organization runtime transfer files are fully accepted and committed
with this checkpoint. Focused65759 PASSED9 cases/175.602s (7 transport/2 actual DI). Full15063 ENDED
PASS603.54s with5,475 fresh cases/642 suites, zero failures/errors/skips,4,263 unchanged
inputs and zero Android lint/Detekt findings. Its source hashes still match after
applying the non-overlapping existing-store candidate. [Contract](collaboration-space-runtime-transfer.md),
[evidence](measurements/collaboration-space-runtime-transfer-2026-11-06.json). Transport commit is15facc913e792bfab94f6c32a61651f1275c9d57. Its index was verified
against all4,263 accepted source hashes; the newer working-tree changes were excluded.
Queue68d first control42310411055 and native42310411202 are now running. SDK4 control
42306713193 is successful and audited. Transport first control42313584270/native
42313584318 are pending. Do not redispatch or displace those pending source runs.

## Next actions

1. Phone51 is installed; [release evidence](measurements/collaboration-release51-2026-11-06.json)
   records the exact APK and owned offline/online acceptance. Keep the original pending
   report open. Do not drive phone UI. Passive audit driver derives its cutoff from
   the actual installation and corrects measured device clock offset. Passive audits
   at23:24:02 and23:50:00UTC retained no post-install lines for either profile; silence
   proves no delivery outcome. Do not launch the phone to manufacture evidence.
2. Reap proof-build-2/8440. After successful exact-source build, run the prepared
   `native-1/run-native.py proof-build-2` for real SDK4 closed-store/key preservation,
   file contention, interrupted writes and process restart on both x86_64/ARM64.
   Preserve any failed fixture for inspection. Then `check-full.py full-1` runs the
   complete root/Android/browser/quality gate. Transport's full gate is already
   accepted; preserve its original inputs rather than rerunning solely for evidence.
3. Continue original pending-message recovery through the original private store and
   current authority. Keep old-parent/device guards; never reset queues, replace pending
   sends, invent acknowledgements or change original encrypted namespaces. Current
   isolation does not recover an older store's unresolved work.
4. Complete provider-publication preflight, durable local stop/join and target intent,
   current-admitted freeze/drain/publication/adoption, hard-parent recovery and group
   routing. The new transport alone cannot activate this irreversible transition.
Existing-store design and hashes are under closed-handover-next. Its34 focused cases
are accepted; real native and full candidate gates are still required. It preserves
original private files and stages one exact local target only after all old
reservations drain. The codec still requires an initialized original record;
credential rotation with initialized=false remains a distinct unresolved phase.
Provider-publication admission and hard-parent server registration still need their
owning implementation; this local mechanism grants neither.

5. Continue all54 communications PRD groups; delivery remains3 in progress/51 planned,
   complete=false. Calls/meetings and remaining chat scope are not finished.

## First source CI — audit, never redispatch accepted runs

- SDK4 product5081e629: native42306713065 PASSED and is audited (412 cases/70 suites
  on each ABI, zero failures/errors/skips, all original native/worker/notification
  proofs and cleanup pass). Artifact13549485285 x86_64 SHA5a812cfabf56988ff82785942d563812db6146db345bbc60f3859f9e1a01c6be;
  ARM6413548922641 SHAad46667029dfbff4ecee8a1796e10b4e616f8bd31d066c71c77921549d2fc079.
  Control42306713193 is accepted and audited:1,775 Apple cases/195 suites and
  4,410 portable cases/474 suites, zero failures/errors/skips, actual iOS app host.
  Apple artifact13549177558 SHA dd60ef8ec12c8f33455225819a050207e7bbf0348a6cf797df93c246241bbe94;
  portable13548077331 SHA14dd8b4a74881dcf516b02f9dd437ab10a3dfbb89a7a2508cda0c03daad1a183. Private `.work/space-store-inventory-2026-11-06/remote-ci-5081/audit.py` accepts
  those exact runs after completion and verifies immutable artifact digests.
- Its protocol42306715423 **PASSED**, native/classic each14 checks with owned cleanup;
  artifact digests and14 Platform hashes match immutablee0dbb567. [Evidence](measurements/collaboration-sdk4-product-ci-2026-11-06.json).
- Queue68d848b7: first control42310411055/native42310411202 are running.
  IDs are retained in `.work/chat-queue-family-2026-11-06/first-runs.json`.
- Transport15facc91: first control42313584270/native42313584318 are pending; exact
  IDs/head retained under runtime-transfer-next/first-runs.json.
- Prior35df/7ef/f04 and SDK native42291341014 are accepted; do not rerun their sends
  solely for evidence. Actual new-source CI remains separate from physical-phone proof.

## Repositories and immutable artifacts

- Mobile branch`feat/communications`; last program15facc91. SDK4 possession program
  5081e629 passed61 focused cases and7,388 fresh full cases/963 suites in1,581.972s.
  Earlier failed quality run and corrected coroutine assertion remain recorded.
  [Possession contract/evidence](collaboration-space-sdk-possession.md).
- Starter`/home/fajar/hris/compose-fluent-starter`, branch`feat/communications`,5fb4eca
  pushed/clean. Exact reusable quality-source fix excludes private`.work` in both file
  selection and Detekt. Full47695 passed490 fresh cases/824.801s,994 stable inputs,
  lint0 errors/44 existing warnings, actual malformed fixture retained during checks
  then only that owned fixture removed. First Apple42308538803 on exact5fb4eca PASSED and is audited:138 cases/35 suites, actual simulator app and
  associations; artifact13548473056 SHA7517404fa7edc9f8572eeb2670a74adc80f2c34269360b0c04e39bf09955eebc.
  Docs b37f8c8 are pushed, source5fb4eca unchanged; do not redispatch. Prior0ff7e59 retains280 local/138 Apple acceptance.
- Group`/home/fajar/hris/.worktrees/communication-space`, branch`feat/communication-space`,
  c9de3753/program344211f8 pushed. V117–137 remain unshipped/disabled. MainPlatform
  `hris-platform` atf4a2424d; live10485916ee02850cf913793398f7ef3dd1e09afb/schema116.1
  unchanged. Native HRIS CI fixture62ee11818a4928b2598d046c7b3283bf3e416554;
  protocol fixturee0dbb567acc4c5571b82324c515ed8ddc145007d.
- **Published SDK4 is immutable:** release437093395, tag`matrix-sdk-26.10.23-hris.4`,
  target4228599ba659cf68cfea1138e6f8017b0b77c222. AAR SHA256
  `9b6806de02b7d40b8487b54a51b192c114a38c68c6dfe35c68faf50d3b6bfd21`.
  Never run publisher/resume publisher again. Strict normal Gradle uses this public
  dependency. Native sources and wrapper under `.worktrees/matrix-*-inventory-2026-11-06`
  are frozen. Original SDK3 release/source and Bima/upgrade fixtures stay retained.
  [Actual SDK3→4/private identity proof](measurements/collaboration-sdk-existing-identity-consumer-2026-11-06.json).

## Persistent working constraints

Push every commit. One heavy local job at a time; no build/emulator overlap. Leave
unrelated CodePush, containers and Java processes alone. No subagents are authorized.
Announce EACH phone install, explicit serial, install-only; never launch or drive its
UI. The owned Bima emulator is5562 with original
`.work/demo-chat-device-2026-11-05/bima/userdata.img`; retain every sidecar/key/transaction.
Apple physical testing is waived, not Apple implementation or compile/runtime gates.
Domain owns policy/use-case orchestration, repositories coordinate datasources,
datasources perform raw I/O, ViewModels own presentation. Follow AGENTS.md; do not
move recovery logic into generic helpers. No new goal has been created.
