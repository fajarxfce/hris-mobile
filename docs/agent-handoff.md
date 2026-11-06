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

The ONLY heavy local job is **15063**, organization runtime transfer **full-1**,
under `.work/space-store-inventory-2026-11-06/runtime-transfer-next`. Its ten applied
Kotlin files are **UNCOMMITTED**; keep all inputs frozen until the job ends. Nine new
files add typed freeze/drain transport plus seven MockEngine cases; the tenth extends
actual AppContainer DI resolution. Focused65759 PASSED175.602s with9 cases (7 transport/2 actual DI), zero failures/
errors/skips,4,263 unchanged inputs and format/architecture checks. [Contract](collaboration-space-runtime-transfer.md),
[evidence](measurements/collaboration-space-runtime-transfer-2026-11-06.json). Freeze uses
the original operation/current native owner and receives original legacy device/version1;
drain observes bounded server slots. There is no UI, owning workflow, server call,
completed transfer, or live enablement. Full acceptance remains required. No local
emulator is active. Preserve prepared/source/result manifests and failed attempts.

## Next actions

1. Phone51 is installed; [release evidence](measurements/collaboration-release51-2026-11-06.json)
   records the exact APK and owned offline/online acceptance. Keep the original pending
   report open. Do not drive phone UI. Passive audit driver derives its cutoff from
   the actual installation and corrects measured device clock offset. First passive
   audit23:24:02UTC retained no post-install lines for either profile; silence proves
   no delivery outcome. Do not launch the phone to manufacture evidence.
2. Reap full15063 and audit unchanged source hashes, fresh report counts and all
   required root/Android/browser/quality targets. Preserve the accepted focused evidence.
   Commit/push accepted transport separately. First queue-source CI must start before
   another app-source push can displace its pending runs.
3. Continue original pending-message recovery through the original private store and
   current authority. Keep old-parent/device guards; never reset queues, replace pending
   sends, invent acknowledgements or change original encrypted namespaces. Current
   isolation does not recover an older store's unresolved work.
4. Complete provider-publication preflight, durable local stop/join and target intent,
   current-admitted freeze/drain/publication/adoption, hard-parent recovery and group
   routing. The new transport alone cannot activate this irreversible transition.
5. Continue all54 communications PRD groups; delivery remains3 in progress/51 planned,
   complete=false. Calls/meetings and remaining chat scope are not finished.

## First source CI — audit, never redispatch accepted runs

- SDK4 product5081e629: control42306713193 and native42306713065 are running/pending
  audit. Private `.work/space-store-inventory-2026-11-06/remote-ci-5081/audit.py` accepts
  those exact runs after completion and verifies immutable artifact digests.
- Its protocol42306715423 **PASSED**, native/classic each14 checks with owned cleanup;
  artifact digests and14 Platform hashes match immutablee0dbb567. [Evidence](measurements/collaboration-sdk4-product-ci-2026-11-06.json).
- Queue68d848b7: first control42310411055/native42310411202 are **pending behind SDK4**.
  IDs are retained in `.work/chat-queue-family-2026-11-06/first-runs.json`.
- Prior35df/7ef/f04 and SDK native42291341014 are accepted; do not rerun their sends
  solely for evidence. Actual new-source CI remains separate from physical-phone proof.

## Repositories and immutable artifacts

- Mobile branch`feat/communications`; last program68d848b7. SDK4 possession program
  5081e629 passed61 focused cases and7,388 fresh full cases/963 suites in1,581.972s.
  Earlier failed quality run and corrected coroutine assertion remain recorded.
  [Possession contract/evidence](collaboration-space-sdk-possession.md).
- Starter`/home/fajar/hris/compose-fluent-starter`, branch`feat/communications`,5fb4eca
  pushed/clean. Exact reusable quality-source fix excludes private`.work` in both file
  selection and Detekt. Full47695 passed490 fresh cases/824.801s,994 stable inputs,
  lint0 errors/44 existing warnings, actual malformed fixture retained during checks
  then only that owned fixture removed. First Apple42308538803 on exact5fb4eca is
  running; do not redispatch. Prior0ff7e59 retains280 local/138 Apple acceptance.
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
