# Agent continuation — HRIS Mobile

## Active collaboration implementation — 31 October 2026

The user approved the complete chat/call/meeting plan. Branch
`feat/communications` starts from `7e12251a` with no inherited edits. The canonical
[54-group PRD](https://github.com/fajarxfce/hris-platform/blob/feat/communications/docs/collaboration/prd.md)
and [delivery ledger](https://github.com/fajarxfce/hris-platform/blob/feat/communications/docs/collaboration/delivery.json)
live in Platform. P0 begins with actual isolated Matrix/LiveKit protocol proof;
no collaboration feature is accepted yet. Preserve current Firebase/HRIS
identity and build-4 phone installation. Continue all P0–P8 phases with matching
tests, source evidence and pushed atomic commits. Prove reusable foundations
here before porting them to Starter. The completed prior scope below is history,
not closure of this new implementation request.

The Android sender now creates a version-12 encrypted room with `invited` history
while the enrolled browser peer is offline. It observes SDK encryption/member
state, sends through the paused/resumed queue, then closes its SDK. The driver
stops only the proof process before the peer joins, loads history and decrypts.
Encrypted logical IDs, the SDK transaction mapping, standard refresh and unchanged
signing key still pass. [Invitation evidence](measurements/collaboration-native-invited-2026-10-31.json)
records two scenarios in 18.486s (33.494s owned run), with complete cleanup. Full
root/debug/lint checks passed in 48s: 2,002 desktop cases, zero lint errors and 47
warnings. An initial Detekt long-method failure was fixed by extracting result
serialization; no check was suppressed.

Native CI pins Platform `54eb3a667ebe47ff06fcb0c2a973824215f383b8`. The
hosted [41204075013](https://github.com/fajarxfce/hris-mobile/actions/runs/41204075013),
passed every job/step at `e6dcb5ea`. Both scenarios passed in 34.359s
(82.746s owned run) with complete cleanup; driver hashes match the pin. This proves Android sender to an already-enrolled
offline browser invitee, not native recipient-before-join or Swift interoperability.
Never-enrolled recipients still need a pending product outbox until eligible keys
exist. Durable restart/coalescing, product sessions/content and UI remain pending.
The dedicated emulator is stopped with regular HRIS data retained. No physical
phone, live service or employee message was touched; no Starter port is due for
the isolated fixture. Continue all P0–P8 implementation.

The native queue/extra-content experiment now passes in both directions through
actual Android 35 and browser E2EE. `org.hris.message_id` remains inside encrypted
content. The SDK's paused queue emits its local transaction, resumes, then maps
that same transaction to the server event; standard refresh preserves the signing
key. Both scenarios pass in 29.090s with complete cleanup. [Queue evidence](measurements/collaboration-native-logical-queue-2026-10-31.json)
records tested source/APK hashes, twelve driver cases, full root checks with 2,002
desktop cases and debug assembly/lint (52s; zero errors, 47 warnings).

Two earlier runs failed at synchronous `Room.enableSendQueue` with native
`InternalException`. Safe class/method/line diagnostics locate the call without
logging provider messages/credentials. The client owner's asynchronous
`enableAllSendQueues` passes and is used for connectivity gating. This experiment
does not prove process-restart queue persistence or recipient deduplication;
implement durable logical IDs, event aliases/tombstones and conflict handling
before offline acceptance. No Starter port is due for this isolated proof.
The dedicated emulator is stopped with regular HRIS data retained; no phone action
occurred. Native CI now pins Platform `d0731afe`; inspect its exact result after push.

Hosted native queue proof passed at exact `e8a5ceb3` in
[41198048864](https://github.com/fajarxfce/hris-mobile/actions/runs/41198048864).
Every job/step succeeded. Both provider scenarios passed without failures/skips
in 84.382s, with complete cleanup and an independently built APK. The
[measurement](measurements/collaboration-native-logical-queue-2026-10-31.json)
retains that result; its driver hashes match pinned Platform `d0731afe`.
Continue HRIS gateway/content integration and durable recipient reconciliation.

P0 native checkpoint: [the separate Android protocol fixture](collaboration-protocol.md)
uses Matrix Rust Kotlin SDK 26.10.23. Actual Android 35 ↔ browser encrypted text
exchange and the existing web/media scenario passed together in 24.590s, with
full owned cleanup. Platform driver/source `a9100ece` records 12 guard tests and
ciphertext evidence; [Mobile source/artifact hashes](measurements/collaboration-android-protocol-2026-10-31.json)
bind the tested APK. Full root checks and debug lint passed in 4m42s, including
2,002 desktop cases across 349 suites (no failures/errors/skips); lint has zero
errors and 45 dependency-version/fixture-packaging warnings. Native CI is defined with that exact Platform driver; record
its result separately. This is still P0: product sessions, native durable queue,
Apple bindings, MatrixRTC and native media remain pending. No Starter code port
is due yet and no phone install was performed. Android proof source is `477f9c60`.
Its first hosted run `41181935938` failed before compilation because the SDK
setup action still defaults to Google's retired `tools` package. The workflow
now requests `platform-tools` explicitly. Corrected run
[41182128950](https://github.com/fajarxfce/hris-mobile/actions/runs/41182128950)
passed at `eefc4121`: both real-provider scenarios passed without failures/skips
in 66.016s including cleanup. [Hosted evidence](measurements/collaboration-android-ci-2026-10-31.json)
records the independently built APK hash and pinned Platform driver. The first
setup failure remains historical; it was not a passed native proof.

Apple binding work now lives in `tool/collaboration-apple-proof`, pinned to Swift
SDK 26.10.27. Its hosted workflow must pass real native key-store reopening,
wrong-key rejection and the iOS device compile. Linux has not run those checks.
The SDK requires iOS 16; only this isolated package changes its minimum so far.
Hosted run [41182072489](https://github.com/fajarxfce/hris-mobile/actions/runs/41182072489)
passed at `e210508a`: one actual native key-store case and the iOS device build,
using Xcode 16.4 / Swift 6.1.2. The resolved package revision and
[measurement](measurements/collaboration-apple-binding-2026-10-31.json) are retained
in Git. This does not prove Apple provider interoperability or native media.
Continue protected HRIS integration while those remaining P0 gates stay explicit.
The local dedicated emulator has been stopped after successful Android proof;
its regular HRIS data and the physical phone were untouched.

The workspace policy now recognizes `COLLABORATION` separately from the HRIS
Inbox's `COMMUNICATIONS` key. The existing data-boundary test retains future-key
filtering and round-trip validation. All 28 workspace-domain and nine workspace-data
cases pass; dev-debug assembly passed in 3m54s. No installation was performed:
this is policy plumbing for the ongoing chat integration, not a delivered chat UI.
Platform schema 105 and its dashboard policy UI carry the matching identifier.
No Starter port is appropriate for this HRIS company-module catalog entry.

The standard native Matrix refresh mechanism now passes against real Synapse and
the browser. Four-second native access credentials expire, the SDK refreshes, the
session delegate saves both replacement tokens and encrypted chat continues with
the same device signing key. Both provider scenarios passed in 30.586s with owned
cleanup. [Refresh evidence](measurements/collaboration-native-refresh-2026-10-31.json)
records hashes, twelve driver cases and the corrected full root check: 2,002
desktop cases, zero lint errors, debug APK built (2m54s). An initial Detekt long
method failure led to the separate `ProofSessionStore`; no check was suppressed.
Native CI pins Platform `1db0f9fe`; hosted run `41187875527` passed at
`d799e5cf`, both provider scenarios in 54.657s with full cleanup.
This is private fixture storage, not a production Keystore implementation. Use
SDK standard refresh, never restore again on an initialized SDK client. Continue
protected HRIS sessions/provisioning/gateway and actual chat UI. No Starter port
is due for this isolated proof. The dedicated emulator is stopped, data retained;
no physical phone action occurred.

Updated 31 October 2026 (UTC). The release-configuration audit and independently
verified Starter port are complete. This employee application was generated from
Compose Fluent Starter and is the proving ground for reusable foundation changes.

## Completed mobile layout and compact-filter review — 31 October 2026

The requested employee-page layout/component review, fictional sample data and
follow-up compact filters are complete. Mobile is the proving ground; reusable
components and tests were then ported and independently checked in Starter.

- Mobile application `e5af27fd` includes the earlier form/detail/document fixes
  from `b73031be`, then lightweight status filters and paired period chevrons on
  the right. Full labels reflow at narrow widths or large text. Month/year/week/
  revision limits and each feature's filter scope remain unchanged.
- Starter production `58623f5`, portable test `df2906f`, closure `44d164c` include
  the identical generic foundation. No HRIS policy, routes or configuration was
  copied. No additional port is pending.
- Platform `1363abee` created 23 fictional visible records for the existing
  user-linked employee plus leave balance examples, using authorized APIs and
  retained idempotency receipts. Do not seed again with a fresh manifest. No
  identity binding, permissions or real payroll/payment action was introduced.

Configured **devRelease 1.0.0 / build 4** is installed on the WireGuard phone.
SHA-256 `20adbce11c8f727d3cf80d24d4909ebfd9dfd1f8c94d39327dd70f91bd701e71`,
24,127,587 bytes. Signed assembly passed in 142.752s. Install-only and pulled-APK
verification passed in 25.852s; app identity, first-install time and data were
preserved. No phone UI was launched/driven. **Future builds require versionCode
>= 5 and the same signer**. Test/docs-only commits after `e5af27fd` do not require
another installation. The normal Firebase configuration and private API are
verified in the actual artifact. Never deliver an unconfigured emulator fixture.

The identical configured release was inspected on the dedicated Android 35
emulator: month/year/week stepping, status selection/reset, normal/200% text,
dark appearance and landscape. Overtime content begins 72 dp earlier and Expense
content 80 dp earlier than build 3, measured on matching fictional rows. See the
[review and screenshot galleries](layout-review-2026-10-31.md) and
[complete measurements](measurements/compact-filters-2026-10-31.json). The emulator
was restored to portrait, normal text and light appearance; only its own session
was logged out, then its process was stopped. Private helpers, raw captures and
immutable APKs remain under `.work/ui-review-2026-10-31/`; no secret belongs in Git.
An old emulator auth fixture has build 3: rebuild it with a sufficient version
before using it again, and keep it on the dedicated emulator only.

Final local checks passed **2,002 Mobile desktop cases / 349 suites** (212 shared
UI cases) and **331 Starter cases / 114 suites**, without failures/errors/skips.
Hosted validation is complete and explicitly matched to unchanged production
source: Mobile Windows/Web/Apple passed in [41170243851](https://github.com/fajarxfce/hris-mobile/actions/runs/41170243851)
at `e5af27fd`; corrected Linux root/browser/Android/app-link verification passed
in [41171544190](https://github.com/fajarxfce/hris-mobile/actions/runs/41171544190)
at `f742e09d`. Starter Windows/Apple passed in `41170061606`, and corrected Linux/
Android/Web verification passed in `41170810070`. The earlier full runs retained
failed Linux tests; they are not represented as entirely green runs.

Those test corrections preserve the intended contracts: host fonts may wrap
390 dp filter labels, while 640 dp proves a single row; checkpoint publication
may briefly replace facts collectors through `flatMapLatest`, so tests wait for
a settled collector and count fake subscriptions atomically. Saved facts,
no-automatic-fetch, offline recovery, ownership and keyboard/disabled-action
assertions remain. Production code and APK bytes did not change for these fixes.
The manual CI `verify` scope keeps default all/PR/push and Apple-only behavior.
Physical iPhone testing remains explicitly waived; Apple simulator tests and the
Xcode app build passed.

Installed guidance is recorded with source pins in the review: Chris Banes
`compose-component-design` and `compose-ui-testing-patterns`, plus
`ui-ux-pro-max`, under `/home/fajar/.codex/skills/`. They become discoverable on
subsequent turns; their guidance was read and applied to this change.
No implementation, validation, delivery, sample-data write or user input remains
pending for this review. Earlier sections below are retained history.

## Previous Android installation — build 2, 31 October 2026

The user explicitly requested the latest release build on the WireGuard phone.
Source `5ffb7300` retains the accepted `d170810f` application/build/test trees.
A signed `devRelease` APK, version `1.0.0` / build **2**, is now installed on
`10.77.77.3:5555` (Samsung SM-A725F, API 36). It is non-debuggable and uses
`http://10.77.77.1:8088/api/v1/`. Release assembly/vital lint passed in 132.050s.
The existing development signing certificate was deliberately reused for the
private in-place update and existing Firebase registration; this is a release
build, not a new production distribution signing identity.

The 24,111,115-byte installed APK exactly matches SHA-256
`fa9b20a67148d323b4d8975da147871df6a1d9b576cf9202d0cd8c8cdf45919d`.
Install-only plus installed-byte verification took 24.772s. The package app ID and
first-install time are unchanged; no uninstall, data clear or UI launch occurred.
[Measurements](measurements/android-release-phone-2026-10-31.json) retain build
inputs, source continuity, certificate/package/API checks and installation proof.
The immutable APK and prior debug APK are retained locally under
`.work/release-phone-2026-10-31/`.

That installation established versionCode 2; the current build-4 delivery above
supersedes it. The repository default remains 1 and would be a downgrade.
Preserve the same certificate and install in place. No application source or
server change, Firebase distribution upload or generic Starter port was needed.
Earlier phone hashes/builds below are retained history.

## Completed workspace audit — 30 October 2026

The full Platform/Mobile/applicable Starter audit is complete. Platform commit
`64eb0d14` records all 61 initial requirements and eleven detailed module reviews
accepted, with final [CI 41094285531](https://github.com/fajarxfce/hris-platform/actions/runs/41094285531)
at `93438f99`: all ten jobs passed, including 3,090 API cases, 104 active worker
cases, 1,111 mocked browser scenarios, 56 actual workflows and schema-103 recovery.
GitHub temporarily returned stale step details; final logs, reports and artifact
digests prove completion. No cancellation, rerun or source change was required.

The private API/worker now run verified application `2c2a59e3` after a fresh
schema-103 data/key checkpoint. Health, migration history, Google/person bindings,
active native session IDs, document integrity and runtime environments are verified;
SMTP stays disabled. Dashboard `1e0522c0` is unchanged. [Current Platform handoff](https://github.com/fajarxfce/hris-platform/blob/feat/firebase-auth/docs/agent-handoff.md)
and [runtime evidence](https://github.com/fajarxfce/hris-platform/blob/feat/firebase-auth/docs/measurements/final-development-rollout-2026-10-30.json)
retain current ownership, rollback images and recovery locations.

Mobile's [fifteen-area review](feature-review-2026-10-30.md) and
[source/CI index](measurements/feature-review-2026-10-30.json) remain accepted at
`f4f101a1`, against unchanged full-CI source `d170810f` (`41077194465`). The normal
private APK with application `3c6db4eb` is already installed and required no new
installation for this backend-only update. Actual Android Google/Home and FCM
acceptance remain source-scoped; physical iPhone testing is explicitly waived.
Apple simulator tests and the complete Xcode app passed.

All 657 identical shared files and 60 differences were reviewed. HRIS business
policy stays downstream; no additional generic Starter port remains. Starter's
accepted source `77063862` retains its independent full CI and separately proved
artifact-upload correction. This closing update changes documentation only.
No implementation, local test or CI remains active. For new work, continue proving
reusable changes in Mobile before porting them, and use the current Platform
inventory rather than reopening historical pending paragraphs below. The review
explicitly records undefined future extensions, including post-approval overtime
fact amendments; it does not label them implemented or claim production release.

## Retained acceptance history

Earlier pending statements below are historical; use the active queue above.

Current metadata acceptance: exact source `d170810f` passed all four jobs in
[CI 41077194465](https://github.com/fajarxfce/hris-mobile/actions/runs/41077194465).
Downloaded reports verify 1,994 desktop, 1,676 browser, ten build-logic and 104
Apple simulator cases, with zero failures/skips. The full Xcode app, three Android
variants, app links and BOM passed. App source remains `3c6db4eb`; the intervening
change only adjusts the bounded metadata test deadline and records native evidence.
The normal private API APK is installed on `10.77.77.3:5555`; installed SHA-256 is
`1a114fbc388c843b45749a8475296f506563493a38bccabd146323ceb87cf791`.
Install-only took 5.777s; no UI was launched/driven. [Final evidence](measurements/document-metadata-final-ci-2026-10-30.json)
retains every job/step, report hashes, failed predecessor and installed binary.
Platform's current runtime is schema 103 with dashboard snapshot `1e0522c0`;
older schema-101/phone-72341ef6 paragraphs below are historical. SMTP stays disabled.

Platform Administration correction `3c8fb15a` is locally accepted and pushed;
full CI `41081153648` is active. Reporting review reproduced seven final-policy
MFA failures and the corrected focused run passes; broader verification continues.
These are HRIS business changes. No new Starter implementation port is due.


The full PRD review remains active. Platform's [audit](https://github.com/fajarxfce/hris-platform/blob/feat/firebase-auth/docs/feature-audit-2026-10-30.md)
now retains forty-one reviewed rows across Approvals, Communications, Identity, Expenses,
Leave, Documents, People and Administration; twenty Platform rows and full Mobile review remain. Keep the original
scope; accepted increments do not establish whole-product completion.

- Platform `8014d733` adds optional announcement email, atomic publication capture,
  bounded SMTP delivery and private observations. Local acceptance, schema-102
  recovery and all ten [full-CI jobs](https://github.com/fajarxfce/hris-platform/actions/runs/41005223374) passed.
- `bbb99b48` exposes immutable shared approval history. `fcfdaf26` corrects
  read-completion MFA/delegation expiry; 60 approval API and 13 domain cases,
  quality/packages and two actual API/browser workflows passed.
- `737d39a7` corrects thirteen Communications read-expiry reproductions. All
  106 selected API, 13 domain and three repository cases passed, plus the actual
  API/worker/browser/SMTP workflow (37.1s; 1.0m with setup).
- `a45d5a9b` records Identity's five initial rows and eight detailed sections.
  Its source trees match accepted `8014d733`; downloaded reports verify 190 API,
  35 domain, 22 data, 17 worker and 95 Chromium cases, including eight real workflows.

- Platform `7a1c0251` and `8e5dd3db` complete Document read/command assurance.
  Twenty read reproductions and nineteen command/enrollment reproductions now
  pass; one inventory-replay scenario was an already-correct positive control.
  All 243 selected API, 18 domain and nine data cases, quality/packages and four
  actual API/browser workflows passed (42.5s). Four Document rows retain acceptance. The explicit category requirement
  in `documents.02` is reopened after the People audit found missing metadata.

- Platform `5b60aba3` completes the Expense assurance/input correction and its
  six-row/nine-section review. Twenty-nine genuine failed cases now pass, plus
  ordinary-scope controls and delegator-account wait regressions. Broad/final runs
  cover 335 distinct API, 20 Expense domain and 115 Reporting cases; quality/packages
  and four actual browser workflows pass (1.2m with setup). Scope includes current
  enrollment, receipt and decision delegation, and stable admitted command inputs.

- Platform `c68b721a` and `70b92e59` complete Leave read/command/delegation
  assurance and its five-row/eleven-section review. Ten reader and twenty-five
  command/scope failures now pass. Broad and final targeted runs cover 485 distinct
  API/OpenAPI cases (471 broad plus fourteen added cases), with 65 domain cases,
  quality/packages, six actual browser workflows (50.4s) and three actual worker
  workflows (53.4s). Scope includes final MFA, original receipt approval, beneficiary
  exclusion, stable policy input and current delegated access. Eight added capacity
  compatibility cases preserve ordinary/direct reads without permitting oversized
  delegated decisions; fixture setup failures are explicitly excluded from defects.

[Approval CI](https://github.com/fajarxfce/hris-platform/actions/runs/41009137720)
passed all ten jobs for `fcfdaf26`. Communications/Documents source `8e5dd3db`
passed all ten [CI 41013958569](https://github.com/fajarxfce/hris-platform/actions/runs/41013958569) jobs.
Expense [CI 41018107365](https://github.com/fajarxfce/hris-platform/actions/runs/41018107365)
passed nine jobs and all fourteen worker scenarios, but exceeded the five-minute
step budget (5.2m); later storage/export checks were skipped. Platform `84d37ad6`
records the failure and sets bounded eight-minute worker/twenty-minute job budgets.
Final Leave source `70b92e59e7b41bdafe9f9d1c4b9d5106412777f5` passed all ten jobs in
[CI 41023645590](https://github.com/fajarxfce/hris-platform/actions/runs/41023645590),
including unchanged Expenses, worker, private exports and recovery. Platform
`7dae1e26` records the exact outcomes and the failed predecessor.

People reader correction `b0b7c21c` passed 427 API/OpenAPI, 50 domain and fourteen
repository cases, quality/packages (1124s), three actual browser workflows (36.5s)
and two actual worker/browser workflows (37.3s), including setup. The later command
and Organization correction has fifty reproduced failures plus four prior controls;
all 54 focused API, 52 domain and fourteen data cases plus quality/packages passed
(142s). Final broad acceptance passed 497 API cases. Two legacy worker-import
fixture failures were corrected; all 104 ordinary worker cases then passed with
one independently accepted manual test skipped (226s). Production/package hashes
matched the broad API run. Three normal browser workflows passed in 36.1s and two
worker/browser workflows in 36.8s, including setup. Platform `158b81ac` commits the
complete correction and evidence. Exact source `158b81acce89f637dbf715bb573fffb7b99b92c9`
passed all ten jobs in [CI 41032184925](https://github.com/fajarxfce/hris-platform/actions/runs/41032184925).
Platform retains the terminal job/step evidence in `06f8dede` and its predecessor.

The audit confirmed missing distinct document category and foreign identity/document
business-validity metadata in `documents.02` and `people.03`. Upload deadlines are
operational; tax residency already belongs separately to Payroll. The previous
Documents review omitted category, so that row is reopened. See the
[gap and continuation](https://github.com/fajarxfce/hris-platform/blob/feat/firebase-auth/docs/document-identity-metadata-gap-2026-10-30.md).
People command acceptance is complete. Platform `27e7f77e` implements metadata
backend/dashboard with 689 distinct accepted API cases, 104 ordinary worker cases,
27 database, 18 domain and nine data cases, plus 4,054 dashboard cases, 25 mocked
and five actual browser workflows. [CI 41038369597](https://github.com/fajarxfce/hris-platform/actions/runs/41038369597)
tests exact `27e7f77e722da9e8ac2bde47836353ef92dcc3b3`: all eight shards passed,
but recovery rejected schema 103 because the reviewed verifier still stopped at 102.
Dashboard integration also passed: the original run finished with nine successful
jobs and one recovery failure. Platform `292a30f1` fixes the verifier; 42 Python tests
and actual schema-103 restore passed (57.356s), including metadata/history/RLS and
restored constraint behavior. [Replacement CI 41043798660](https://github.com/fajarxfce/hris-platform/actions/runs/41043798660)
passed all ten jobs for exact `292a30f14498b2eee97a9255f22fe645639658e5`, including
normal/worker browser integration, private exports and schema-103 recovery. Platform Git
retains every terminal job/step and the downloaded recovery summary: 104 objects,
2,098,795 bytes, restored metadata/history/RLS/constraints and completed cleanup.
The original run remains recorded as a failure; no CI run is pending for this source.

Mobile's [owned metadata projection](document-business-metadata.md) is locally
validated. A real mapper-loss reproduction now passes. Root `:check` passes 1,869
desktop tests, Document Chromium suites pass 123 cases, and the dev APK builds.
[Measurements](measurements/document-metadata-read-model-2026-10-30.json) preserve
source/APK hashes and each earlier fixture/setup/lock correction. Format 2 refreshes
canonical records even at unchanged document versions and when the server later
advertises support. Old encrypted facts remain visible during staging; failed
cleanup never removes a confirmed read denial. Finance/Leave DocumentEvidence
stays separate from owned category, identity number and validity facts.

Initial-upload metadata `41fe79f9` is committed/pushed and locally validated: the capability-aware form, typed
confirmation, authenticated payload and encrypted intent retain category/identity/
printed dates. Legacy omission and pending original operations remain unchanged.
All 1,878 desktop tests, 131 Document Chromium cases, root quality checks and dev APK
assembly pass (163.698s). The assembled form exposed and now fixes feedback hidden
above a long form. Read [upload evidence](measurements/document-upload-metadata-2026-10-30.json)
for earlier fixture failures, the real reproduction, source and APK hashes.

First definite upload rejection recovery is also locally validated. Two genuine
baseline failures now pass; unknown codes and earlier uncertainty remain pinned.
The fresh form retains prior fields, and explicit confirmation replaces only the
exact observed rejected intent while keeping file bytes and proof. Cancellation,
competing writes, capability loss and read-denial restoration are covered. Full
root checks, dev APK and Document browser suites pass in 189.354s: 1,888 desktop and
140 browser cases. [Evidence](measurements/document-upload-rejection-2026-10-30.json)
records the baseline controls, method-length correction and source/APK hashes.

The separate metadata header/history/correction transport is locally validated.
It preserves legacy unknown facts, bounded contiguous history and the exact confirmed
correction/receipt across retry; headers no longer depend on a published binary.
Ten new cases pass, including authenticated Ktor payloads and denial/cancellation
boundaries. Root checks, dev APK and both Document browser suites pass in 184.233s:
1,898 desktop and 150 browser cases, with no failures/errors/skips. Exact source,
case names and APK hashes are in [transport evidence](measurements/document-metadata-remote-2026-10-30.json).

Metadata storage and per-document admission now also pass complete local validation.
Account-owned encrypted headers/history commit at most twenty rows and survive
restart/reauthentication; a page decrypts exactly one header plus its selected rows.
Tests retain 1,000 immutable revisions and cover stale writers, scope changes,
cancellation, lost keys, manipulated ciphertext and pending/failed admission storage.
Root checks, dev APK and both Document browser suites pass in 184.621s: 1,912 desktop
and 164 browser cases, without failures/errors/skips. See [storage evidence](measurements/document-metadata-cache-2026-10-30.json).
The existing workspace key is read-only; no database primitive or Starter port changed.

Admitted metadata reads and the actual detail/history route are now locally validated.
Thirteen domain cases and two assembled UI workflows cover ownership/capability,
finite directory/history acquisition, cancellation, local history after credential age,
pending/failed denial recovery and explicit storage-observer restart. Two genuine
failures in the new publication path exposed policy expiry during cache/admission
writes; both now pass with the earlier denial retained. Root quality, Android dev
assembly and Document Chromium suites pass in 197.437s: 1,927 desktop and 177 browser
cases, all without failures/errors/skips. Three narrow screenshots were inspected.
[Read/UI evidence](measurements/document-metadata-read-ui-2026-10-30.json) records
source/APK hashes, actual cases and the earlier compilation/method-length corrections.

Correction command contracts and encrypted storage are also locally validated.
The existing shared CommandStore retains at most 128 account commands, one per
document, with immutable input, exact record/revision checks, bounded authenticated
envelopes and the shared command key. A prior uncertain attempt cannot become a
proven rejection; original receipts and terminal outcomes remain immutable. Three
domain and six actual SQLite/IndexedDB cases cover restart, sessions, capacity,
resource slots, stale writers, lost keys, corruption, cancellation and late accounts.
Full root checks, dev APK and Document browser suites pass in 193.331s: 1,936 desktop
and 186 browser cases, all zero failures/errors/skips. Initial focused tests passed
116 domain/69 data cases in 40.635s before the final terminal assertions.
[Command storage evidence](measurements/document-metadata-command-store-2026-10-30.json)
retains exact sources, tests and the uninstalled APK hash.

Command intake, scoped observation, manual delivery and explicit review are now
locally validated. Confirmation stores immutable input without HTTP; replay checks
current policy/ownership, persists uncertainty before sending, and uses the original
operation after timeouts or later header edits. Denials fence projections even when
outcome/admission storage fails. Acknowledgement requires acquired current metadata;
it never replaces a newer header with an old receipt. No automatic delivery task was
registered. Fourteen domain scenarios cover those boundaries and release per-resource
subscriptions. The initial twelve failures were a read-only fixture, not production
defects; the corrected write fixture and separate read-only control pass. Focused
retry passed 130 cases in 29.808s, then the final overview case was added. Full root
checks, dev APK and Document Chromium suites pass in 180.515s: 1,950 desktop and 200
browser cases, all without failures/errors/skips. [Orchestration evidence](measurements/document-metadata-command-flow-2026-10-30.json)
retains sources, exact cases, setup failure history and the uninstalled APK hash.

Mutable working-copy contracts and encrypted storage are also locally validated.
A bounded draft retains incomplete input and its reviewed header; ordinary saves
cannot change that baseline. The existing ReplicaStore/workspace key holds one
16-KiB authenticated checkpoint per document, protected by exact-copy and revision
checks. Copies reopen across sessions without age expiry. Indexed overview pages
read twenty copies plus a lookahead; a full page decrypts exactly twenty envelopes.
Two domain and six actual SQLite/IndexedDB cases cover restart, stable paging,
concurrent edits/deletion, key loss, corruption, cancellation and late accounts.
Focused tests passed 133 domain/75 data cases in 41.438s; full root checks, dev APK
and Document browser suites pass in 180.730s: 1,958 desktop/208 browser cases, all
without failures/errors/skips. [Draft storage evidence](measurements/document-metadata-working-store-2026-10-30.json)
retains exact cases, sources and the uninstalled APK hash.

Working-copy access and acknowledgement cleanup are now locally validated. Ten
new domain cases cover reopening the original baseline without published binary
facts, exact save/delete recovery, concurrent edits, read-only retention, pending
cancellation/timeouts, ownership changes and denial-before-storage subscription.
Confirmed command acknowledgement removes only a matching no-later draft; failed
cleanup retains the receipt and a concurrent replacement survives. Focused domain
checks passed 143 cases in 37.376s; full root checks, dev APK and Document browsers
passed in 182.812s: 1,968 desktop/218 browser cases, all zero failures/errors/skips.
[Working-copy orchestration evidence](measurements/document-metadata-working-flow-2026-10-30.json)
retains exact cases, source and the uninstalled APK hash.

Catalog resource admission is now locally validated. An actual assembled failure at
`36ddd5ed` showed a denied document's title/file still visible after returning from
details. The catalog now observes at most twenty resource gates, hides the denied
record, preserves other rows and the original cursor, and retains a neutral recovery
action. Unrelated catalog sync cannot clear this denial. Three domain, one state and
one assembled workflow cover subscription disposal, failed admission storage,
pending downloads and offline/pending/successful recovery. The first focused domain
failure was incomplete metadata in the new fixture and is recorded separately.
Focused checks passed 146 domain/four state/one UI case in 100.848s. Full root checks,
dev APK and Document Chromium suites passed 183.001s: 1,973 desktop/221 browser,
all zero failures/errors/skips. Two narrow screenshots were visually inspected.
[Catalog admission evidence](measurements/document-metadata-catalog-admission-2026-10-30.json)
retains the genuine reproduction, fixture correction, source and uninstalled APK hash.

The typed correction editor is now locally validated. Its serial writer coalesces UI
edits into storage revisions and retains the exact pending mutation after a lost local
acknowledgement. Back/confirmation/review flush edits; incomplete drafts reopen with
the original reviewed header. Confirmation and manual retry preserve the original
command, while acknowledgement uses acquired current facts and keeps newer drafts.
Three assembled flows cover retained incomplete input, successful correction, lost
response plus a later server edit, explicit stale-baseline reset and denied/offline
recovery. Two presentation cases cover coalescing and late acknowledgements. A real
new feedback failure put the stale notice above a scrolled form; explicit refresh now
reveals it. Control readiness also waits for pending initialization and avoids redundant
local reloads. Fixture permission, selector and timing failures remain recorded separately.
Two additional domain failures on `8ca77f19` exposed single-command storage errors
masking known/later resource denial. Per-document admission now precedes storage and
remains observed after storage failure. All 148 Document domain cases pass, and final
root checks, APK and Document browsers passed in 178.280s: 1,980 desktop/223 browser,
zero failures/errors/skips. The final full run repeats all three new assembled flows;
six screenshots were visually inspected. [Editor evidence](measurements/document-metadata-editor-2026-10-30.json)
retains source/APK hashes, actual cases, earlier failures and practical limits.

The independent retained overview is now locally validated. Catalog opens **Document
corrections** even when a published binary row disappears. Local commands/drafts
retain their original input, resource admission and draft page cursor; row selection
opens the existing editor or neutral admitted recovery. Opening/reloading does not
send HTTP or replay commands. A 45-copy domain case verifies bounded 20/20/5 paging
and released subscriptions. Typed neutral restriction targets prevent private titles
from appearing during resource recovery. Two new presentation regressions exposed
stale private rows when either local reader failed; both admitted projections must
now be ready before private rows display. Five state cases cover that boundary,
denial union, reload and command/draft display deduplication.

Both actual-storage/controlled-HTTP application workflows passed, including the
retained incomplete draft after binary deletion/offline recovery, and the original
uncertain correction retried after a later administrative edit. Final root checks,
APK and Document browsers passed in 190.845s: 1,987 desktop/224 browser, zero
failures/errors/skips. The full run repeats both workflows. Three screenshots were
inspected. [Overview evidence](measurements/document-metadata-changes-2026-10-30.json)
retains exact sources, reproduced failures, actual cases and the uninstalled APK.

Account's Pending actions now includes the separate metadata summary and direct
overview link. Six domain cases cover original outcomes, exact draft deduplication,
untouched input, age-independent bounded pages, resource/company admission, failed
storage, disposal and delayed session replacement. At most 128 commands and twenty
working copies contribute; a next cursor marks a lower bound. Known resource gates
remain observed through storage errors. Unknown/denied resources hide all counts.
The actual-storage/controlled-HTTP Account flow resumes the same draft, reloads
without additional HTTP, and counts one original uncertain delivery with no duplicate
draft. The existing Leave flow and large-text/Indonesian layout controls also pass.
Full root checks, dev APK and Document browsers passed in 173.186s: 1,994 desktop/230
browser cases, zero failures/errors/skips. Two screenshots were inspected; [summary
evidence](measurements/document-metadata-pending-2026-10-30.json) retains exact
sources, cases, runs and the uninstalled APK hash.

The actual Android application `3c6db4eb` now passes owned API/PostgreSQL/private
Garage acceptance, committed in Platform `1e0522c0`. Two offline process restarts
retain incomplete input and then the read-only original uncertain correction.
Retry after an independent later admin edit confirms the same operation/body;
Done removes its matching draft and command. All seven summary groups empty;
the current version-4 admin header, three history revisions and binary revision
remain intact. Summary reloads stay 36→36 and 48→48. The fixture observed 78 native
requests and exactly two successful company writes; it passed in 432.451s with
owned cleanup and five inspected screenshots. [Native evidence](measurements/document-metadata-native-2026-10-30.json)
retains the APK/source hashes, initial fixture-lifetime failure and precise limits.

Full CI `41071828262` at `3c6db4eb` passed Web/Windows and iOS simulator compilation/
tests, but Linux failed the 1,000-revision browser storage case at Mocha's default
two-second deadline. A module-local bounded twenty-second deadline preserves all
assertions; all 75 Document data browser cases pass locally in 21.607s. The complete
Apple app also passed, with 104 tests in 28 downloaded suites and all terminal
Apple steps successful. [First CI evidence](measurements/document-metadata-first-ci-2026-10-30.json)
retains its overall failure and exact source. Replacement full CI `41077194465`
is running at `d170810f03852f46a948f9f4d5dea9ca4104bdbd`; inspect every final job
and artifacts before accepting it. Do not relabel the failed predecessor.

Next: complete hosted Mobile/Apple checks, then coordinate the controlled schema-103
API/static-dashboard/Mobile rollout. Finish the detailed People review and continue
remaining Platform/Mobile/Starter scope. Keep shared finance/leave evidence free
of private identity numbers and preserve legacy omitted payloads. The browser
configuration covers an HRIS-only metadata module; Starter's generic database
capacity suite already has the same bounded I/O deadline. No implementation port
is due. The full goal remains active.

The private API remains schema 101 with SMTP disabled; preserve the accepted Google
binding and installed phone. The dashboard serves an isolated baseline directory
documented in Platform's handoff, so local builds cannot replace it. The new APK
has not been installed. Coordinate the controlled schema-103 API/static-dashboard/
Mobile rollout after the complete metadata feature is validated. Phone updates are
install-only with advance notice; physical iPhone testing stays waived.

## Distribution configuration — accepted

The audit fixes selected release API precedence over per-environment project
properties, optional app-link configuration through signing and packaging, and
31 missing SBOM POM verification entries. The original artifact hashes remain
unchanged. Android checks the normal test configuration before compiling the
selected release; Xcode forwards the selected backend/API explicitly. The 15
portable files retain Starter's application IDs, demo/remote choice and three-job
CI. HRIS's remote-only policy, build-version UI fixtures and explicit dev-network
XML attributes remain in Mobile.

Mobile source `3dc81d00` passed all 4 jobs in full
[CI 40909156570](https://github.com/fajarxfce/hris-mobile/actions/runs/40909156570). Downloaded Apple reports
confirm 104 tests in 28 suites, zero failures/errors/skips;
compilation/tests/Xcode took 791s/122s/1295s. The complete Xcode app, native/domain
artifacts and explicit selected distribution API/backend assertions passed.

Starter port `77063862` passed all 3 jobs in full
[CI 40916187442](https://github.com/fajarxfce/compose-fluent-starter/actions/runs/40916187442). Downloaded Apple reports
confirm 84 tests in 24 suites, zero failures/errors/skips;
compilation/tests/Xcode took 724s/81s/744s. The complete Xcode app, native/domain
artifacts and explicit selected distribution API/backend assertions passed.

Actual local signed Android APK/AAB/SBOM exercises passed in 215.572s on Mobile
and 136.647s on Starter. Both verify the selected release API and native manifest
in APK and AAB, actual signatures, retained domain files and checksums. Mobile
passed 1,860 desktop cases; Starter passed 326 desktop cases in 112 suites. Both
passed 17 distribution regressions. Normal dev builds and production SBOMs were
restored afterward (39s Mobile, 23s Starter), with fixture API/host values absent;
Starter's normal backend remains demo. Every disposable signing key was removed.

A workflow-only follow-up at Mobile `d7e445a8` and Starter `4c794e7` retains
`.well-known` files in the manual artifact upload. The pinned action's actual
`@actions/glob` 0.5.0 selection reproduced five files with both domain statements
missing on each signed fixture; the correction selects all seven and every
checksum matches. Both workflows include hidden files only in the packaged
artifact directory. This separate proof does not invoke upload; application/native
code and ordinary CI definitions remain at the full-CI sources above.

No CI, local check or reusable port remains pending for this audit. No distribution
workflow, upload or phone installation was performed; the installed Mobile APK
remains `72341ef6`. The private server and accepted Google binding are preserved.
Real signed Apple IPA, APNs, public domain/DNS/TLS activation, physical GPS and
production operation retain their separate target requirements. Physical iPhone testing was
explicitly waived; simulator and unsigned Xcode proof does not imply device delivery.

See [distribution evidence](measurements/distribution-2026-10-29.json).

## Previous implementation checkpoint — HTTPS links

Final Mobile source `72341ef6` passed all four jobs in full
[40895095518](https://github.com/fajarxfce/hris-mobile/actions/runs/40895095518).
Downloaded Apple reports confirm 104 cases in 28 suites, zero failures/errors/skips
and the complete Xcode app; compilation/tests/Xcode took 701s/77s/1040s.
Starter port `738db36` independently passed all three jobs in full
[40895798634](https://github.com/fajarxfce/compose-fluent-starter/actions/runs/40895798634),
with 84 Apple cases in 24 downloaded suites, no failures/errors/skips and Xcode;
compilation/tests/Xcode took 561s/84s/703s. Generated Android/Apple association
checks passed, and the Xcode/Apple artifact success markers were inspected.
No CI or owned local acceptance remains active from this increment.

The authorized phone has application `72341ef6`, installed with `--install-only`.
APK SHA-256 `5b3754d5097eb0a535d1a98cff5bc37e508ea12a008eec897895d9289797fe4d`,
32,872,857 bytes. Its registered certificate/private WireGuard API match, and
fixture values are absent from runtime code and the binary manifest. First-install
time was unchanged; no phone UI was driven. Actual Google/Home acceptance remains
attributed to the earlier `678783b3` user-operated APK. The private API/dashboard
still returned HTTP 200; preserve the Google binding, keys, volumes and running stack.

The implementation queue is complete for this development pass. Physical iPhone
testing was explicitly waived. Public domain/DNS/TLS association, actual APNs,
physical GPS and production deployment remain separate configuration/target
observations; no result for those is inferred from builds or fixtures. Default
native builds do not enable an unconfigured HTTPS host. Reopen implementation for
an observed failure or a documented requirement; do not recreate accepted modules
from old next-step paragraphs.

The source foundation `d36a1bc9` first passed full CI `40887772486` (103 Apple
cases/Xcode) and actual Android HTTPS cold/login/company/scope/offline/archive
acceptance: 28 requests, no company writes, unchanged read/ack state, 3.2m/3.6m.
Only then was the reusable foundation ported. The later Unicode correction first
reproduced a host alias, then passed seven Mobile JVM/seven Chromium cases before
copying the same two files to Starter. Its own root/Android/Chromium checks passed
326 desktop, seven common/four app Chromium and six generator cases in 88s.
[Measurements](measurements/app-links-2026-10-29.json) retain the source boundaries,
initial failures, corrected checks, final full CI and both phone deliveries.

## Current user direction — 29 October 2026

The user explicitly waived physical iPhone testing for this completion pass
("iphone gkpp gk ditest juga") and requested continued implementation through
completion, with each atomic commit pushed. Do not ask for an iPhone again or
keep that test as a completion blocker. Simulator/contract tests and Xcode builds
remain required; do not claim actual iOS sign-in or APNs delivery from them.
Configurable HTTPS admission/native associations and the proven Starter port
are now complete, with the source and destination evidence above.
The controlled domain remains a deployment setting, not a reason to leave the
configurable implementation unfinished. Preserve the accepted Google binding,
phone data and running private development stack.

The HTTPS implementation is now in Mobile: exact environment-host path admission,
optional ignored public registrations, generated domain/Apple files, variant Android
manifest transforms, Xcode preparation and Universal Link handoff. Root/Android
and Chromium passed in 7m25s with 1,859 desktop cases and 26 common/app Chromium
cases; build-logic tests cover removal and malformed inputs. Configured artifacts
and full hosted CI passed before the foundation port.
Platform's public `/app/...` handoff passed 4002 unit and 14 related browser cases
without session or business requests.
See [app links](app-links.md); no real domain is enabled by the default build.

## Start here

1. Read `AGENTS.md`, inspect `git status`, the branch, recent commits and upstream.
   The working branch at this checkpoint is `feat/native-push`.
2. Read [validation](validation.md), [local-first coverage](local-first.md), the
   affected module contract and [template updates](template-updates.md).
3. Read the [Platform handoff](https://github.com/fajarxfce/hris-platform/blob/feat/firebase-auth/docs/agent-handoff.md)
   and [remaining PRD delivery](https://github.com/fajarxfce/hris-platform/blob/feat/firebase-auth/docs/completion.md#remaining-delivery-sequence).
   Backend/dashboard work is on Platform `feat/firebase-auth`, with application
   integration `b7f42070` / schema 101 and subsequent native acceptance fixtures.
   Full CI at `9345c19b` passed all ten jobs after correcting recovery report upload
   and a closing-report browser assertion; its exact run is in the Platform handoff.
   Starter work is on `fix/push-token-lifecycle`.

The three local checkouts are siblings under `/home/fajar/hris`. A fresh clone
must be able to continue from tracked evidence; local `.work` logs, screenshots
and `/home/fajar/hris/PRD-PROGRESS.md` are supplementary. The user authorized ongoing
implementation and a push after every atomic commit. This does not authorize a
release. Preserve unrelated edits and keep implementation, tests and docs together.

## Current phone delivery: installed after authorized key replacement

Current implementation adds iOS Firebase email/password configuration loading,
Apple bundle restrictions and an owned Google system-browser PKCE/code exchange.
All three matching public plists are installed locally and ignored. Mobile first
passed local password checks at `64039536`; Apple run `40853359016` compiled and
passed the four reader cases but failed one existing cancellation fixture (81/82
native cases passed). `196c1171` replaces both Firebase transport fixtures' racy
Boolean assertion with explicit bounded cleanup acknowledgement. Its Apple
follow-up [40855843460](https://github.com/fajarxfce/hris-mobile/actions/runs/40855843460)
passed 82 simulator cases and full Xcode before the password/configuration port
to Starter `8a75de5`. Starter root/Android, 308 desktop and 31 Chromium cases passed.
Its independent Apple run `40861505322` also passed 62 native cases in 18 downloaded
suites and the full Xcode app, with no failures/errors/skips.

The Google increment passes 65 focused desktop identity cases and public dev OAuth
configuration preflight. Root checks, Android assembly and 82 identity/application
Chromium cases passed in 3m23s, with 1,854 cases in the desktop report set. Full run
[40858176601](https://github.com/fajarxfce/hris-mobile/actions/runs/40858176601) at
`43cd8ec6` passed all four jobs. Downloaded Apple reports confirm 97 native cases
in 26 suites, zero failures/errors/skips, and the complete Xcode app. This closes
the Mobile verification gate for the generic Google port. Starter `e0c6071`
passed root/Android, 320 desktop and 46 Chromium cases; its full
[run 40864341588](https://github.com/fajarxfce/compose-fluent-starter/actions/runs/40864341588)
passed all three jobs. Downloaded Apple reports confirm 77 native cases in 22 suites,
zero failures/errors/skips and the complete Xcode app. Compilation took 526s,
tests 87s and Xcode 589s. The generic Google port is accepted. Direct
iOS-configured HTTPS password preflight also
returned Firebase proof for the exact owned fictional fixture, without HRIS
exchange or binding changes. Source/run details are in
[measurements](measurements/apple-firebase-2026-10-29.json).

Actual iOS sign-in/APNs still require device/provider acceptance; injected-browser
native tests and an unsigned simulator build do not claim that result. Physical iPhone
testing is waived for this completion pass; the [optional iOS procedure](ios-acceptance.md)
records future setup, observations and cleanup. The provider/owned-browser/crypto changes are
reusable; HRIS session policy and configuration stay here. See the
[Firebase contract](firebase-auth.md) and [validation](validation.md).

The user subsequently tried Google on the phone. Firebase confirmed the exact
verified Google identity; Platform `533472ca` records the successful explicit
binding after retiring the fictional password UID. The user retried and confirmed
company/Home, with one new native session independently visible in authorized
server metadata. Actual Google → Firebase → HRIS sign-in is accepted on the phone.
The old emulator session is revoked, with cached data retained; do not reactivate
its old Firebase binding over the real user's account. Private paths and recovery
details remain in Platform's current runtime record.

The user requested installation on the WireGuard-connected Samsung SM-A725F and
subsequently authorized uninstall/reinstall after the signing-key mismatch was
explained, including loss of local HRIS login/drafts/data. Both uninstall and the
install-only command returned **Success** on `10.77.77.3:5555`. The signing-key
question is resolved; do not ask for that approval again.

The installed normal dev APK is `dev.fajar.hris.mobile.dev`, version 1.0.0 / code 1,
32,853,128 bytes, SHA-256
`1883d1da41fdeabe77165de9fc75f1d106ff7583ae93aa92878940c7a2addd0e`.
The phone's installed APK hash matches the prepared artifact. Package metadata
reports the new installation and `stopped=true`, `notLaunched=true`; no app launch
or phone UI interaction was performed. This build uses application `678783b3`,
built at `f740d658` in 26s, with the normal `http://10.77.77.1:8088/api/v1/`
endpoint. See [initial rejection and successful delivery](validation.md#phone-update-signing-key-mismatch).
The local HRIS app data was removed with the user's authorization.

The API was unavailable during installation. The user then authorized continuing
development startup: Platform `703af975` records a new persistent private `hris-dev`
stack at schema 101, with actual HTTP 200 API health from this phone and a dashboard
user service at `http://10.77.77.1:5173`. Firebase Management confirmed the exact dev
app and registered this APK's SHA-1; its web client still matches the installed
configuration. No replacement APK is needed for that provider registration.
See [current development runtime](https://github.com/fajarxfce/hris-platform/blob/feat/firebase-auth/docs/current-development-runtime.md)
for ownership and private configuration/access paths. Fictional company/employee
provisioning is complete: operator MFA, worker-delivered local invitation, standard
employee access, independent person link, weekday schedule, demo policies and
independent approvals. The same normal APK passed actual Firebase email/password
login, company selection and Home on the owned Android 35 emulator. Its document
catalog synchronized a real worker-inspected PDF and the detail displayed
**Downloaded**, 734 B. Platform's private database/content/key checkpoint passed
isolated restoration, followed by successful host/phone network and browser checks.
See [runtime evidence](https://github.com/fajarxfce/hris-platform/blob/feat/firebase-auth/docs/measurements/development-runtime-2026-10-29.json).
The emulator is stopped with this persistent demo account's data retained.
There is no application change or generic Starter port in this increment.
Human Google sign-in is accepted above; physical FCM/location acceptance remains
separate from successful package installation and network health. The prior
loopback APK remains ignored at `.work/fcm-native-validated.apk`; do not distribute
it to this phone.

## Accepted result: actual Android FCM passed

The sender credential question is resolved. Platform `55288bc4` installed the supplied service
account privately outside Git and accepted two actual FCM publications on the
owned Android 35 emulator, using unchanged Mobile `678783b3`. Both foreground and
background notifications displayed generic copy; tapping the latter opened the
correct company/Inbox item without changing either item's unread/unacknowledged
version-zero state. The 55 native requests made no company mutation. Disable/logout
removed the server token/hash. Fixture 6.2m/6.6m with setup; all owned resources
are cleaned and emulator data retained. See [exact evidence and timing limits](validation.md#actual-fcm-foreground-and-background-delivery).
The initial manual-readiness and two-minute observer timeouts remain recorded;
the final 350-second observer passed without changing application behavior.

There is no Mobile implementation change or generic Starter port in this increment.
The existing SDK/queue/navigation foundation passed this actual provider case.
Android Google exchange was subsequently accepted above. iOS/APNs, verified HTTPS associations and physical-location
acceptance still need their separate inputs. Do not ask for the supplied FCM
credential again; its private host path is in Platform's current handoff.

## Previous result: Apple CI correction passed

The earlier local application change `678783b3` corrected the English Expense count label to
`Expense items: %1$s`, after inspecting the accepted native screenshots. Root
checks and Android dev assembly passed in 2m31s; the initial SDK-path setup failure
and source/result boundary are recorded in [validation](validation.md#expense-item-count-copy).
Earlier native fixtures and hosted CI below still refer to `02743a9f` application
sources. No Starter port applies to this HRIS copy edit.

Full run `40815794384` finished: Windows, Web and Linux/Android passed.
Its overall conclusion remains failure because Apple timed out.
Apple timed out during cold test compilation, not an observed compilation error.
See [CI correction](validation.md#apple-ci-cold-compilation-budget). The workflow
now separates compile/test/Xcode time limits, preserves all three test targets,
and supports manual `scope=apple` with independent concurrency and retained reports.
Apple follow-up [40817592306](https://github.com/fajarxfce/hris-mobile/actions/runs/40817592306)
passed at `451a2c4d`: 27 simulator tests in ten downloaded JUnit suites, no
failures/errors/skips, complete unsigned Xcode build and retained artifact. Compile
took 8m3s, simulator execution 52s and the Xcode step 18m54s. The generic workflow
correction is accepted in Starter `1817e45`: independent Apple run
[40820482699](https://github.com/fajarxfce/compose-fluent-starter/actions/runs/40820482699)
passed 27 simulator tests, the full Xcode build and artifact upload. No port is
pending. [Measurements](measurements/ci-2026-10-29.json) retain both Mobile runs,
the later copy-only checks and Starter proof.
Those local native fixtures are all stopped/cleaned. iOS/physical inputs
remain external; the FCM credential and actual Android delivery are now accepted above.

## Accepted evidence: native Attendance capture recovery

Platform `a837320f` verifies actual Android/API/PostgreSQL/worker response loss,
offline cold restart and exact replay of an explicit capture without location.
Stable summary reload stayed 21 → 21; one version-zero event retained its original
capture/receipt time. All six local pending rows cleared while Home/server still
required independent review with zero accepted minutes. There were 30 requests/two
intended writes; fixture 4.6m/5.2m with setup. All owned local resources are cleaned,
location restored and emulator data retained. See
[validation](validation.md#attendance-capture-response-loss-on-android).

Cross-platform CI `40815794384` was dispatched at `b0a95391`, with the same
`02743a9f` application sources used by the native fixture. Its three non-Apple jobs
passed; the separately corrected Apple job and validated Starter port are recorded
above. Platform full [40820912608](https://github.com/fajarxfce/hris-platform/actions/runs/40820912608)
then passed all ten jobs at `9345c19b`, including real API/worker/export integration
and schema-101 restore with a downloaded sanitized artifact. Those CI runs are
complete; no generic port arose from the native HRIS business fixtures. The newer
Starter Google port has its separate accepted full run above. Physical/iOS acceptance
still needs its actual external inputs. Android Google, the supplied FCM credential
and Android delivery are accepted above.

## Accepted coverage: read replicas and commands

The [local-first coverage](local-first.md#current-coverage-and-continuation) now
matches all nine `ObserveCompanyData`/`SyncCompanyData` registrations and the six
pending-action feature observers. Its old next-work list incorrectly still called
document feeds and the shared pending summary unfinished. Directory, private
profile and document catalog storage were checked against their account-storage
mappers and company partitions. Expense/Leave/Overtime working-copy and file/
attendance command contracts already cover the current employee editors.
This is a source/documentation reconciliation, with no new runtime acceptance or
application change; previous measured outcomes remain in their feature ledger.

Platform's payroll period comparison passed 119 API, 94 worker and 12 data tests,
3,997 dashboard tests, 23 distinct Chromium scenarios and an actual five-actor
January/February workflow with retained tax continuity and live access withdrawal.
Push delivery observations also passed schema-101 recovery. Neither administrative
feature needs a Mobile/Starter port. Continue missing acceptance and concrete PRD
gaps without repeating completed implementation or the resolved FCM credential question.

## Accepted evidence: native upload and inspection review

The actual Android/API/PostgreSQL/worker/Garage fixture passed in 8.7m/9.2m with
setup. One committed chunk lost its response; offline restart retained one transfer
and retry preserved the original command. A stopped scanner-unavailable job became
one review item, surviving a second offline restart. Explicit inspection retry,
cancellation, local deletion and logout passed. All six summary rows became empty
before local deletion. Independent server checks observed 57 requests, nine intended
company writes, one unpublished cancelled revision and four single-attempt chunks.
Summary counters stayed 32 → 32 and 42 → 42. See
[complete evidence](validation.md#interrupted-upload-and-inspection-review-on-android)
and [Platform's fixture](https://github.com/fajarxfce/hris-platform/blob/feat/firebase-auth/docs/native-upload-acceptance.md).

The unchanged `02743a9f` loopback APK was reused; no implementation/Starter port is
due. All owned resources were cleaned and emulator app data retained. Platform
subsequently accepted actual elapsed export retirement, including the unchanged
one-hour physical grace and lost deletion receipt retry, at `1abdbce9`. iOS/physical
cases still need their separate setup. Actual Android Google exchange and
foreground/background FCM hints were subsequently accepted above.

## Previous checkpoint: native Firebase registration recovery

Actual Android Firebase Messaging token acquisition and HRIS registration now
pass response loss, offline restart, original-command replay, explicit disable and
logout. The final fixture observed 51 requests, one registration, two identical
PUTs, one disable, version 1 and no retained server token. No company writes were
made. Full acceptance took 9.7m/10.3m with setup. The final emulator APK SHA-256 is
`2b603fff2e40e31ee9cf5a45e694ea6aae8c96ae6c72cd4168d920c2e239c0c9`.

Two HRIS-specific fixes were required: clamp submillisecond server expiry to the
session's retained millisecond boundary; and complete retirement when a previously
owned family returns the exact absent-session result. Other expiry overruns,
errors and accounts remain rejected/retained. The first upgrade recovered an
already retained registration command; the second cleared an absent family before
new registration. See [full evidence and corrections](validation.md#firebase-registration-and-offline-recovery-on-android)
and [Platform's tracked driver](https://github.com/fajarxfce/hris-platform/blob/feat/firebase-auth/docs/native-push-acceptance.md).

Root checks passed 1,840 desktop/UI tests plus tool/static checks; identity browser
suites passed 64 domain and 49 data cases. Android assembly passed. Local setup
requires `ANDROID_HOME=/home/fajar/Android/Sdk`; browser tests use
`CHROME_BIN=/home/fajar/.cache/ms-playwright/chromium-1248/chrome-linux64/chrome`.
The first command omitted these environment settings and is not a passing browser
result; the corrected full command passed in 1m59s. Services, control files and
forwarding were cleaned, connectivity restored, and emulator data retained. No
phone was connected. Generic SDK sources did not change; no Starter port applies.

At this earlier registration checkpoint, server FCM credentials were unavailable.
The user subsequently supplied the file and actual Android FCM delivery passed
above. Continue Google token exchange and iOS/physical acceptance when their
inputs are available; do not repeat the resolved credential question.

## Last accepted increments

- Partial-download recovery now passes actual API/PostgreSQL/worker/Garage/Android:
  one retained MiB and transfer count 1 through offline process restart, summary
  no-op HTTP (31 → 31), explicit resume from byte 1,048,576, native save matching
  3,145,841 bytes/SHA-256 and six empty summary rows afterward. The server observed
  49 requests and zero company mutations. Fixture 9.7m/10.2m with setup; unchanged
  loopback APK reused. All owned services, control files, forwarding and public
  copy were removed; emulator app data retained. See
  [executed evidence](validation.md#partial-download-recovery-on-android) and
  [Platform fixture](https://github.com/fajarxfce/hris-platform/blob/feat/firebase-auth/docs/native-download-acceptance.md).

- `c650e0d7` adds an actual unconfirmed Inbox command: server commit
  followed by response loss, Pending actions count 1 through offline cold start,
  local summary reload with no HTTP (30 → 30), exact-operation Retry and zero
  pending afterward. The independent server observed two identical read commands,
  version 1 and no acknowledgment across 42 requests. Fixture 6.0m/6.4m with setup;
  APK assembly 45 seconds. All owned resources were removed and the emulator
  stopped without wiping data. See [executed evidence](validation.md#inbox-command-response-loss-on-android)
  and [Platform's tracked procedure](https://github.com/fajarxfce/hris-platform/blob/feat/firebase-auth/docs/native-acceptance.md).
- `60456c48`: real Android 35/API/PostgreSQL/worker URL and Inbox archival
  acceptance. Cold-login/company gating, repeated/resumed navigation, scope/query
  rejection and offline restoration passed. Independent server reads proved no
  implicit read/acknowledgement or company writes. Archival stayed enforced after
  offline restart. See [exact URL evidence](deep-links.md#android-url-acceptance).
- `1c1f9104`: actual Android/API Pending actions acceptance for pristine and edited
  Leave drafts, offline process restart, local summary refresh, reconnect and
  explicit deletion. Subsequent cases now cover all six feature observers; consult
  their individual evidence above without generalizing to every possible state.
- `9a557dcc` records cross-platform Firebase CI. Provider implementation `d191c61d`
  passed 1,837 desktop/UI tests, 119 related Chromium cases and Android assembly.
  Actual Firebase email/password completed HRIS exchange, MFA, company selection
  and logout; the temporary provider account was removed. Google chooser/Back
  passed, but actual Google token exchange did not run.

Reusable Firebase adapters are already ported to Starter `9a13791` and documented
at `a16325e`. Notification resource paths, token retirement and incoming-link
ownership were also ported; consult the file/commit ledger in template updates
before repeating a port. The latest native registration increment also fixes the HRIS metadata/cleanup
policies described above.
Platform worker observations/global audit are HRIS administration work, and the
native transfer fixture changes no application implementation; no Starter port is due.

## Next work and actual external inputs

All six Pending actions feature observers now have actual Android/API recovery
examples: Inbox, Attendance, Overtime, submitted Leave, saved Expenses and document
transfers/inspection review. See the specific validation sections for unchanged
summary counters, exact replay, final server invariants and cleanup; they do not
prove every state combination or physical target. Nine company data-sync registrations
and existing employee working copies are reconciled in [local-first coverage](local-first.md).
Do not repeat these features or native cases because an older checkpoint called
them next work. Continue concrete uncovered requirements or observed failures.

Platform full CI `40820912608` passed all ten jobs. The earlier Mobile and Starter
Apple correction runs also passed. New Mobile provider CI `40858176601` is accepted;
Starter Google port run `40864341588` also passed all three jobs as recorded above.
Those provider runs are complete. The newer HTTPS run `40887772486` also passed all four jobs and 103 Apple
cases/Xcode. Its Starter port and the subsequent Unicode correction both passed their final
full CI runs above. Do not repeat completed provider-foundation
work or use the older provider CI as proof of the HTTPS increment.

Firebase project `cmp-fajar` has Email/password and Google enabled. Android configs
exist locally in ignored `apps/android/google-services.json`; do not commit provider
credentials. Actual FCM foreground/background delivery and notification navigation
and Android Google/Home are accepted above. Matching public iOS plists are installed.
Configurable native/route association is now implemented. Public domain
publication, APNs setup and physical location remain deployment observations.
Physical iPhone testing is waived; only the domain question remains pending.
Do not repeat it while waiting or request an iPhone again. The supplied sender credential is installed privately
in Platform's host configuration. Public Android config is a separate file; do
not repeat the resolved credential question.

## Validation and device ownership

Use JDK 21 and `ANDROID_HOME=/home/fajar/Android/Sdk`. On headless Linux, root
checks use `xvfb-run -a ./gradlew --no-daemon :check`; Android development assembly
is `:apps:android:assembleDevDebug`. Select related Chromium tasks from the owning
module and inspect [development](development.md) and [distribution](distribution.md).
Do not infer device/provider acceptance from compilation or fixture responses.

For this workspace, supply tool locations explicitly (adjust paths on another host):

```sh
JAVA_HOME=/usr/lib/jvm/java-21-openjdk \
ANDROID_HOME=/home/fajar/Android/Sdk \
ANDROID_SDK_ROOT=/home/fajar/Android/Sdk \
CHROME_BIN=/home/fajar/.cache/ms-playwright/chromium-1248/chrome-linux64/chrome \
PATH=/usr/lib/jvm/java-21-openjdk/bin:$PATH \
xvfb-run -a ./gradlew --no-daemon :check
```

Append the affected browser/build targets to that command. Keep fixture-specific
API overrides local and explicit; this command preserves normal app configuration.

Use AVD `hris-mobile-dev`, serial `emulator-5560`; never wipe its retained data.
The last acceptance used a build-specific loopback API override. Preserve the
normal environment configuration and inspect any fixture before starting another.
The accepted fixtures were stopped and private connection files removed. Phone
delivery is install-only under `AGENTS.md`: notify before installation, select its
serial explicitly and do not launch or operate its UI while it is in use.

Update this handoff and validation after each accepted increment. Include the
exact remaining work, known failed attempts and corrected causes, resource cleanup
and whether a reusable change still needs its validated Starter port.
