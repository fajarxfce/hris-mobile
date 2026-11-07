# Agent continuation — HRIS Mobile

This is the active checkpoint. The [historical handoff](agent-handoff-history-through-build50.md)
retains earlier work verbatim. Detailed measurements below are authoritative; do not
restart accepted builds, native sends or SDK publication solely to recreate evidence.

## Current next step — 7 November 2026 01:28UTC

Proof-only storage diagnostics pass root check/20 fresh cases/91.471s,4,269 stable
inputs, proof assemble/format/architecture and Detekt0. Exact APK4b62c4999cbde3d78c44e020d493e588d909f494ec307cd059d4d377e9ae197d
is retained in .work/native-storage-diagnostic-2026-11-07/accepted-proof.apk.
No shipping runtime policy changed. Native CI now pins Platform940c181c parser;
13 native-device+34 HRIS+18 worker-driver cases pass. See
[proof contract](collaboration-native-proof-diagnostics.md).
NEXT: run ONLY the unresolved frozen-draft/priority worker case on fresh owned
emulator data with this diagnostic APK; original Bima userdata/sidecars stay intact.
No heavy local job/emulator is currently active. Do not claim a native fix yet.

Primary/sourceee338fb3/34d507bc is pushed after both fd05 source gates started.
Firstee runs: control42323799690 pending, native42323799725 pending,
protocol42323799691 running. Preserve pending first runs before another primary
source push; commit/push current proof diagnostics on the temporary recovery branch.
15facc91 control42313584270 is audited:4,436 portable/478 suites and1,788 Apple/197
suites, no failures/errors/skips. Original x86 native failure remains unresolved.

Phone52 remains installed; original pending OPEN. Live API2e75fe1e finite operation
logging is healthy, worker10485916/schema116.1 unchanged. Passive5minute observation
from01:18:34UTC captured zero relevant requests; no phone UI/sends. One optional
request to open Bima after server diagnostics is outstanding; do not repeat it.
Caller has one current and one revoked-parent device; the failed target is unknown.
Continue this diagnosis and all54PRD groups after the native investigation.

## Current work — 7 November 2026

The original physical-phone pending-message report is **OPEN**. Release51 fixed
older physical queues blocking current-store batch selection. Its passive log then
revealed a distinct generic native send/retained-queue rejection at00:01:39–41UTC.
All154 traced HRIS HTTP calls succeeded(max387ms), but those Ktor metrics do not
establish native Matrix HTTP success. Provider logs have one send/key query200,
without individual-message matching. No agent message or phone UI action occurred.
[Physical evidence](measurements/collaboration-phone51-send-rejection-2026-11-07.json).

**Release52** is built from pushed **fd05b7318b0f4cc2e562d5d9ec29481923bc563c**.
ARM64 devRelease build72709 PASSED499.447s, lint zero. APK121,022,756bytes SHA256
`88600d7650a5c46e7c77a571c0d2ac2b00fe27849901d41cbf7fc5e9b773dbd0`.
Nondebuggable, existing signer, Firebase/dev API, source revision, backup exclusions,
16KiB alignment and exact immutable SDK4 bytes are verified. The exact APK upgraded
original Bima51→52 in place; two offline and two online room entries passed(visible
rows12/12 offline and12/11 online), with30s same-process online stability and no new
agent sends. Ordinary stale offline authority used guest clock+3900s; clock/network/
ADB privilege were restored. Both screenshots were inspected. Owned emulator5562
stopped00:39:02UTC; original userdata/sidecars remain. Phone install74085 PASSED81.848s
at00:40:30UTC, exact installedAPKhash/sameUID10962/original first-install time preserved,
no uninstall/data clear/UI launch. [Release52 evidence](measurements/collaboration-release52-2026-11-07.json).
Private drivers/results: `.work/release-phone-2026-11-07-build52`. Passive audit00:41:09
retained no post-install lines for either profile; silence proves no delivery. An
optional request to open Bima in52 for diagnostics is pending; work continues.

The diagnostic change maps bounded pinned SDK/Ruma prefixes to finite internal
categories: HTTP status, response decoding, missing Olm/local state, crypto store,
wrong room state, concurrent/previous failure. No private server prose is emitted;
queue/retry/admission policy and transaction identity are unchanged. Focused76606
passed6 cases/93.998s; full28124 passed2,498 fresh cases/329 suites in424.942s, zero
failures/errors/skips,4,267 stable inputs and zero lint/Detekt findings.
[Contract](collaboration-queue-rejection-diagnostics.md),
[evidence](measurements/collaboration-queue-rejection-diagnostics-2026-11-07.json).

Both source commits were separately pushed on temporary`feat/chat-recovery-20261107`:
closed-store handover **d609e31e**, diagnostic **fd05b731**. Once both first15facc91
CI runs started, origin`feat/communications` was fast-forwarded tofd05b731. The local
branch is now also`feat/communications`; release52 correctly records the temporary
branch it was actually built from. Preserve the first fd05 control/native pending
runs below before another source push. Docs-only pushes do not trigger these gates.

## Accepted recovery foundations

- Physical queue isolation **68d848b7fed810984ddb59ca53079442dcac7819** keeps original
  old-store states/revisions/keys/transactions and filters current work before the
  eight-command batch. Seven regressions produced five failures on unchanged code;
  identical tests passed(76 focused). Full57507 passed5,454 fresh cases/639 suites,
  540.556s,4,254 stable inputs and zero lint/Detekt findings. Release51 was installed
 23:22:17UTC6 November, sameUID10962/first-install2026-10-29 15:48:23; original Bima
  twelve-row offline/online acceptance passed with no new sends.
  [Queue contract](collaboration-chat-queue-ownership.md),
  [release51](measurements/collaboration-release51-2026-11-06.json).
- Original runtime transfer **15facc913e792bfab94f6c32a61651f1275c9d57** adds exact
  freeze/drain transport, not an owning migration flow. Focused65759 passed9 cases;
  full15063 passed5,475 fresh cases/642 suites in603.54s,4,263 stable inputs and zero
  lint/Detekt findings. [Contract/evidence](collaboration-space-runtime-transfer.md).
- Closed original-store handover **d609e31e** excludes/drains the exact old physical
  runtime, derives identity from actual private keys under an existing-only lease,
  and durably retains one exact target with original credentials/queue provenance.
  Focused86050 passed34 cases. Actual SDK4/Keystore two-ABI process-death proof11165
  passed120.442s(x86_64 42.045s,ARM64 38.118s), no provider/new sends. Original private
  keys/main database bytes remain; WAL/SHM bookkeeping is not claimed unchanged.
  First proof build72231 failed only Detekt LongMethod; its failure is retained.
  Corrected8440 passed74.868s without suppression. Full50781 passed5,490 fresh cases/
  644 suites in586.14s,4,267 stable inputs and zero lint/Detekt findings.
  [Contract](collaboration-existing-store-handover.md),
  [evidence](measurements/collaboration-existing-store-handover-2026-11-06.json).

## Next actions

1. Release52 is accepted/installed. Use post-install UID logs to distinguish the
   actual native queue rejection; no agent phone UI action or replacement send. An
   empty passive log proves nothing. Original old/new message outcomes remain open.
   The latest optional question requests opening Bima in52 without sending new messages.
   NEW post-install52 logs at00:48:07–10 show retained/send ApiForbidden (403), with
   device-sync/outgoing Unavailable. Passive00:51:35 audit has75 lines/zero fatals;
   rawSHAfb2f098695c1946a05134de02c1be7e5538a793a82356c6a7955473f3d9d0a53.
   Main Platform061cac31 finite rejection diagnostics passed52 cases and deployed
   API-only at01:04:56UTC; worker10485916/provider/DB/schema116.1 are unchanged.
   At01:07:33UTC the server logged collaboration_recipient_unavailable. The precise
   operation/target is still unknown. Read-only original-pair selection finds one
   eligible confirmed device for both Bima and caller; the earlier email-based null
   caller query is invalid evidence. See
   [current rejection evidence](measurements/collaboration-phone52-send-rejection-2026-11-07.json).
2. Interrupted-credential handover has12 accepted Kotlin files in34d507bc, pushed
   on temporaryfeat/chat-recovery-20261107. Primary promotion follows firstfd05start.
   Private drivers/results are under
   `.work/space-store-inventory-2026-11-06/rotation-handover-next`. Initial red95305
   proved5 new cases/3 failures on unchanged codec. Focused64026 passed50 cases/
   236.176s and quality. Actual SDK4 proof24863 passed132.367s on x86_64(45.945s)
   and ARM64(43.52s), with real interrupted credential replacement, process death,
   original keys/main databases retained, zero sends and complete owned cleanup.
   Exact proofAPK is retained under native-1/proof-verified.apk.
   Review found the separate domain inventory policy still rejected false initialization
   plus a handover. Full30680 was deliberately stopped after89.987s before fresh tests,
   source unchanged; its controlled-stop/result remain under full-1. Read-red-1 had
   two expected domain failures plus a fake event-log reuse error. Clearing that per-read
   fake log was the only test correction; read-red-2 passed its expected4 cases/2 failures
   in68.029s with domain production unchanged. After correcting the pure read policy,
   focused10901 passed67 cases/116.74s, zero failures/errors/skips and lint-free Detekt.
   Full96083/full-2 PASSED5,512 fresh cases/644 suites in513.264s,4,267 stable
   inputs, zero failures/errors/skips and zero lint/Detekt findings. No heavy local
   job or owned emulator is active. Do not rerun the accepted native mechanism: its original
   seven files remain identical; only one pure read policy/four read-path tests changed.
   Keep all original keys/namespaces/transaction identities. No group activation.

3. Implement provider-publication preflight and the owning recovery flow: durable
   local stop/join/target, current-admitted freeze/drain/publication/adoption, explicit
   native hard-parent recovery and group routing. Existing local/transport mechanisms
   grant neither server registration nor group activation. Never weaken native-family
   guards, reset queues, replace old pending sends or manufacture acknowledgements.
4. Continue all54 communications PRD groups:3 in progress/51 planned, complete=false.
   Calls, meetings and remaining chat features are unfinished. Phone complaints do
   not cancel the broader task; keep regular user updates and Git checkpoints.

## First source CI — preserve/audit original runs

- **fd05b731**: control42318790930 running from01:19:28UTC, native42318790943 running.
  Protocol42318790936 FAILED before native tests: SDK emulator image download was
  not a valid archive. Classic job passed; original failure retained, no redispatch. Exact IDs/head recorded under
  `.work/phone51-send-rejection-2026-11-07/first-runs.json`. Do not displace the
  pending source runs with a newer primary-branch push or manually redispatch them.
- **15facc91**: control42313584270 SUCCEEDED at01:19:25UTC; original artifacts still need audit; native42313584318 FAILED: ARM64 passed419 cases/71 suites;
  x86_64 had one worker priority-history Storage_Unclassified failure out of419.
  [Original CI failure evidence](measurements/collaboration-first-ci-failures-2026-11-07.json).
  First artifacts/logs are retained under runtime-transfer-next/remote-ci-15 and
  .work/ci-failures-2026-11-07. This app failure remains under investigation. IDs and auditor under
  `.work/space-store-inventory-2026-11-06/runtime-transfer-next`.
- **68d848b7**: control42310411055 and native42310411202 both successful and audited.
  Control:4,422 portable cases/476 suites and1,781 Apple cases/196 suites, actual iOS
  host built. Native:412 cases/70 suites on each ABI, original SDK/retained/worker/
  notification proofs and cleanup pass. Zero failures/errors/skips. Immutable artifact
  IDs/digests in [first-run evidence](measurements/collaboration-queue-family-ci-2026-11-07.json).
- **5081e629 SDK4 product**: control42306713193/native42306713065/protocol42306715423
  all accepted/audited. Control4,410 portable/1,775 Apple cases; native412 cases perABI;
  protocol14 native/classic checks each with cleanup and14 exact Platform hashes.
  [Evidence](measurements/collaboration-sdk4-product-ci-2026-11-06.json).
- Prior35df/7ef/f04 and SDK native42291341014 remain accepted. New-source CI does not
  prove any original physical-phone message delivered.

## Repositories and immutable artifacts

- Mobile`feat/communications` sourcefd05b731; temporary recovery branch retains the
  exact build52 source. SDK4 possession program5081e629 passed61 focused and7,388
  fresh full cases/963 suites in1,581.972s. Earlier failures remain recorded.
  [Possession contract/evidence](collaboration-space-sdk-possession.md).
- Starter`/home/fajar/hris/compose-fluent-starter`, `feat/communications`,docsb37f8c8/
  source5fb4eca355e3d3761c0c976c42b4386a3c7e31aa pushed/clean. Reusable quality-source
  `.work` exclusion passed490 fresh local cases/824.801s,994 stable inputs,lint0errors/
  44 existing warnings. First Apple42308538803 audited138 cases/35 suites, actual host
  and associations; artifact13548473056 SHA7517404fa7edc9f8572eeb2670a74adc80f2c34269360b0c04e39bf09955eebc.
  Do not redispatch. Prior0ff7e59 retains280 local/138 Apple acceptance.
- Group`/home/fajar/hris/.worktrees/communication-space`, `feat/communication-space`,
  c9de3753/program344211f8 pushed; V117–137 unshipped/disabled. MainPlatform
  `hris-platform` atf26f953e; liveAPI2e75fe1e/worker10485916/schema116.1.
  API-only finite rejection/operation logging is deployed; all other runtime inputs
  unchanged. The operation update passed52 cases/81.557s and deployed01:20:40UTC
  in11.048s. FirstMainCI42323441241 running. Phone pending still open. Native CI fixture62ee11818a4928b2598d046c7b3283bf3e416554;
  protocol fixturee0dbb567acc4c5571b82324c515ed8ddc145007d.
- SDK4 release437093395/tag`matrix-sdk-26.10.23-hris.4`, target4228599ba659cf68cfea1138e6f8017b0b77c222,
  AARSHA9b6806de02b7d40b8487b54a51b192c114a38c68c6dfe35c68faf50d3b6bfd21 is immutable.
  NEVER rerun its publisher. Native Rust/wrapper worktrees are frozen. Actual SDK3→4
  private identity/consumer proof is accepted; retain original upgrade fixtures.
  [Evidence](measurements/collaboration-sdk-existing-identity-consumer-2026-11-06.json).

## Persistent constraints

Push each atomic commit; no coauthors. One heavy local job at a time, no build/emulator
overlap. Leave unrelated CodePush/containers/Java alone. No subagents or goal created.
Announce EACH phone install, explicit serial, install-only; never launch/drive its UI.
Original Bima5562 uses `.work/demo-chat-device-2026-11-05/bima/userdata.img`; retain
ALL sidecars/private keys/pending identities. Apple physical testing is waived, not
implementation/compile/runtime gates. Follow AGENTS.md: domain policy/use cases,
repositories coordinating raw datasources, ViewModels for presentation. Generic
foundation fixes go to Starter only after mobile validation; HRIS policy stays here.
