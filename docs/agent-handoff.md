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

Existing-store handover is fully accepted. Full50781 PASSED586.14s:5,490 fresh
cases/644 suites, zero failures/errors/skips,4,267 unchanged source inputs and zero
Detekt findings. Focused34 and real SDK4 two-ABI/native process-death proof also pass
(120.442s; x86_64 42.045s, ARM64 38.118s). All ten Kotlin files are included in this atomic source commit on temporary branch
`feat/chat-recovery-20261107`, while transport15facc91's first CI is pending. Their exact accepted contents are
retained in `.work/space-store-inventory-2026-11-06/closed-handover-next/accepted-source`.
No heavy local job or emulator is active. The three additional diagnostic Kotlin files
are accepted and included in the separate diagnostic commit with this checkpoint. Focused76606 PASSED6 cases/93.998s;
full28124 PASSED424.942s with2,498 fresh cases, zero failures/errors/skips and4,267
unchanged inputs. Zero Android lint/Detekt findings;6 focused and329 fresh full suites are recorded in
[diagnostic evidence](measurements/collaboration-queue-rejection-diagnostics-2026-11-07.json). [Contract](collaboration-existing-store-handover.md),
[evidence](measurements/collaboration-existing-store-handover-2026-11-06.json).

NEW phone51 passive evidence at00:05:47UTC on7 November has164 post-install lines:
ChatQueueRetained_Rejected twice, ChatQueueSend_Rejected once, delivery Unavailable,
and three ChatSync_Generic. All154 traced HRIS HTTP calls are200(max387ms); those
Ktor metrics do not establish native Matrix HTTP success. Zero fatal exceptions.
This is NOT the former native-family mapping failure; inspect actual generic SDK/API
rejection before attributing old/current pending messages. Private sanitized summary
and raw log are under release-phone-2026-11-06-build51/phone51-uid-000547.*.
Prioritize this actual phone rejection while retaining the accepted handover work.
No phone UI was driven and no message was sent by the agent.
Provider logs contain one send and one key query200 at00:01:36, without per-message
matching; one later API error is a client-aborted response. A45s passive wire capture
saw no requests and grants no delivery evidence. The additional diagnostic maps only
bounded pinned SDK/Ruma prefixes to finite categories (HTTP status, response decoding,
missing Olm/local state, crypto store, wrong room state, concurrent/older failure).
It never emits server prose or changes queue policy. Six focused cases including
three new privacy/prefix cases pass; the exact full gate also passed before release52.
Release drivers are prepared under `.work/release-phone-2026-11-07-build52`; no APK
is built. After the gate, use temporary `feat/chat-recovery-20261107` for two atomic
source commits (accepted closed-store10, diagnostic3), push each, then build52 from
that exact pushed branch. This preserves the previous first pending CI while allowing
phone delivery. Fast-forward feat/communications only after both15facc91 first runs
start; that will launch the combined source's first CI. Do not redispatch it or
change either old pending run. Return the local branch to feat/communications at that
point without dropping the accepted/source work. Temporary recovery branch now exists; closed-store d609e31e is pushed. The diagnostic
commit follows it with its accepted three source/test files. Next build52 from that
exact pushed branch. Do not change source or HEAD while the release build is running.
[Physical evidence](measurements/collaboration-phone51-send-rejection-2026-11-07.json).


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
2. Build/verify release52 from the pushed recovery branch and run its owned Bima
   offline/online acceptance, then announce and install-only on the authorized phone.
   Continue tracing actual phone51 generic queue rejection. Read-only server logs for00:00–00:05
   are retained under `.work/phone51-send-rejection-2026-11-07`; use closed categories,
   not raw private bodies/tokens. User has an optional question about new vs old sends
   in build51 pending; do not infer its answer from elapsed time.
   The ten exact accepted closed-store files are committed on the temporary branch.
   Full50781 and native11165 already pass; do not rerun solely for evidence.
   Seven formatted later files and red/green/full/native drivers are prepared only
   under `.work/space-store-inventory-2026-11-06/rotation-handover-next` (not applied).
   Five new regressions precede a separate strict schema3 preserving initialized=false.
   The actual probe interrupts product credential replacement after commit before new
   SDK construction; it does not manually change the saved flag. Preserve schema2 and
   open-client guards. This continuation stays required after the physical rejection.

3. Continue original pending-message recovery through the original private store and
   current authority. Keep old-parent/device guards; never reset queues, replace pending
   sends, invent acknowledgements or change original encrypted namespaces. Current
   isolation does not recover an older store's unresolved work.
4. Complete provider-publication preflight, durable local stop/join and target intent,
   current-admitted freeze/drain/publication/adoption, hard-parent recovery and group
   routing. The new transport alone cannot activate this irreversible transition.
Existing-store design and hashes are under closed-handover-next. Its34 focused cases
and real two-ABI native proof are accepted; full candidate gate also passed. It preserves
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

- Mobile temporary branch`feat/chat-recovery-20261107`; origin feat/communications
  remains0497dd6d/program15facc91 until its first pending CI starts. SDK4 possession program
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
