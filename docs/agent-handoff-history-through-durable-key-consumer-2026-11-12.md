# Agent continuation — communications long run

Updated 12 November 2026, 07:39 UTC.
Continue PRD and push every commit. All 54 PRD groups OPEN. No subagents. One compiler OR emulator.
Follow AGENTS.md and clean boundaries. Phone install-only; announce installs, never drive its UI.

## Durable key consumer accepted locally

Workspace .work/durable-room-key-consumer-2026-11-12, baseline f969fd734a3d5e41026fbb389ad37e9386f98290.
12 files / 5,123 inputs / b9d17f3fe4e7307e3bf1356d2331be6a55522e78f8d707ab862cd79d52ee20cd.
415 JVM +364 browser =779 focused. Full full-1/34352 REAPED,
587.451s; audit 85250 REAPED. Local14,429 =
12391 fresh +2038 verified unchanged. All prior case IDs retained.
18 policy+26 consumer+8 encrypted-storage cases per supported target; existing DI graph asserts one
collector instance. Read measurement for preserved failures/corrections and exact proof/source hashes.

The use case admits current root/session/registered source, explicitly registers the existing parent
listener, classifies physical rooms locally and atomically retains exact receipt+intent+wake before
ACK. Receipt survives worker/other-producer progress and stays stable through3 bounded5s attempts,
with250/500ms backoff. Authority/unavailable failures terminate. A second collector waits on the
lifetime Mutex. Schema9 keeps1–8 compatibility. No application collector activation or native proof.

Publisher/handoff EXECUTED. Source66f537a4 and Group e1bbaa19 pushed (53112/12301 REAPED).
Consumer CI43176939497 dispatched ONCE07:40:00.363508UTC (84948 REAPED), expected17,470 =
12,407portable+5,063Apple. Hosted state exists. No redispatch. Targeted-history43172811544 is
sourcef969fd73:portable12,303 audited (57960 REAPED); Apple pending, expected total17,314. Binding43168158361 FULL17,170
and lifecycle43165090113 FULL17,085 are audited. Mailbox
43161963820 FULL17,004 is audited. Do not redispatch old sources or call protocol checks native proof.

## Current: installed runtime publication under validation

Workspace .work/space-runtime-publication-2026-11-12, baseline66f537a4cba7f13080acc0f734fe669e8ef4d51d.
11 source files APPLIED, NOT accepted/committed;5,127inputs, final manifest
b4c0b77605df85d4ad73c8a6c56a632b2d2d1195f0f649444ad4cb54d2eb0456.
Android1/43735 failed new-class JUnit initialization (one method inferred non-Unit); archived before
fixing runBlocking<Unit>. Android2/70426 passed116/85.684s, but its manifest is pre-final-test-fix.
Focused1/60136 failed common-test type inference in two expected nullable-publication lists; archived
before making those two list types explicit. Production unchanged by both test corrections.
Format3/52115 REAPED passed35.022s. Focused2/42721 REAPED PASS56/368.456s;
browser1/99510 REAPED PASS54/139.727s; Android3/75055 REAPED PASS116/29.819s.
Focused110 and final Android116 audits accepted (Android inline exit0). Full1/89532 ACTIVE.
No other compiler/emulator. Preserve failures before correction/refreeze. Publisher/handoff prepared,
NOT executed; hosted publication plan UNDISPATCHED, expected14,466local/17,519hosted.

Application-sources-draft now has11 private files, NOT reviewed/applied/compiled. State JSON in this
workspace records22owner+3lifetime+2foreground cases and pending metadata-producer audit. Generators
already executed; do not rerun partial generators. No preparer yet. Expected next increment27 local/
portable, zero Apple tests (app host tests); future total14,493local/17,546hosted still provisional.
Native acceptance remains required before claiming production behavior.

Phone81329 REAPED07:50:21.494115UTC,20.014804s timeout. No install; next>=08:23:41.509030UTC.

## Next: application incoming activation and native acceptance

The serial use-case owner must be wired to actual installed runtime publication/retirement and
root/account changes, with cancellation AND join. ObserveCollaborationSpaceIncomingStartup only
proves local root/bootstrap facts; constructing RuntimeReady from those IDs does not prove installation
or make a failed collector restart when the SDK later appears. Read runtime-publication-draft and next-runtime-publication-design.md:11 private files,12 common
+13 Android publication cases, not yet applied/compiled. Actual lease publication/retirement and
per-instance UUID are observed without acquisition. Review next-runtime-publication-state.json
and use the preparer only after the current source is accepted, pushed and dispatched.
Activate sync/source/restore producers coherently with metadata publication and existing worker service;
metadata must precede new history demand, including histories behind the active cursor. Preserve90s
worker inventory/history/primary fairness and no extra transport/client per callback. Native execution
must measure requests, finite retry, keys arriving during a write and offline/process recovery before
claiming the former loops resolved. Apple native key adapter still requires implementation/proof.

SDK restore creates OlmMachine before asynchronous network/backup setup. Do not wait for broad E2EE
initialization or loop registration after an unavailable stream. Raw parent owns final listener disposal.
Phone26630 REAPED07:16:44.346845UTC,20.020912s timeout; next>=07:50:04.367764UTC.
No installation.
[Previous accepted checkpoint](agent-handoff-history-through-targeted-recovery-2026-11-12.md).

## Protected environment and prior evidence

Validated release89 is in .work/release-phone-2026-11-10-build89. It is SDK6, nondebuggable arm64,
123809167B/4c790d2314f53c37bfdc3108ac4b801ab33aa9fc177904001fd16793fd0ef52d. Use install.py
--install-only. No phone UI/reset/uninstall. Main is SDK8, with protected source/wrapper worktrees;
do not rebuild or push those forks, copy old runtime fixtures, or dispatch the legacy SDK4 workflow.
Never resend the recorded Control77/Warm77/Recovery78/Registry82/Parent83/History84/Selected86/Joined87
native messages; Draft66 was never sent. Preserve Bima stores and original keys.

Starter feat/network-inspector-20261107 at badfc0d is clean and accepted906fresh tests; HRIS policy
is not a template port. Inspector's unrelated gradlew.bat change remains protected. Live backend is
90163c32500ff2fa005013eaf83b026d4bdcf5ff/schema116.4, readiness ON/Group OFF; Group migrations117–140
remain undeployed. Do not replace live services from the Group documentation worktree. Firebase
Crashlytics IAM was already verified; never print the home service-account credential.

Full source-specific manifests, exact artifact/report/log hashes, historical CI gaps, SDK hashes,
protected worktrees and earlier native evidence remain in the linked history and measurements.
[Earlier checkpoint history](agent-handoff-history-through-room-key-binding-2026-11-12.md).
