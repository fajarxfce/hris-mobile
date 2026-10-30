# Agent continuation — HRIS Mobile

Updated 30 October 2026 (UTC). The release-configuration audit and independently
verified Starter port are complete. This employee application was generated from
Compose Fluent Starter and is the proving ground for reusable foundation changes.

## Active workspace goal — full feature completion audit

The full PRD review remains active. Platform's [audit](https://github.com/fajarxfce/hris-platform/blob/feat/firebase-auth/docs/feature-audit-2026-10-30.md)
now covers 31 initial rows across Approvals, Communications, Identity, Documents,
Expenses and Leave; 30 initial Platform rows and the full Mobile review remain. Keep the original
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
  actual API/browser workflows passed (42.5s). The five Document rows and ten
  original detailed sections have recorded local acceptance.

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
Final Leave source `70b92e59e7b41bdafe9f9d1c4b9d5106412777f5` is running in
[CI 41023645590](https://github.com/fajarxfce/hris-platform/actions/runs/41023645590), including unchanged Expenses and the revised workflow.
Observe its terminal result without cancelling it. Continue People, the remaining
Platform requirements, then Mobile and applicable Starter behavior. The full goal
remains active; this module acceptance does not complete the PRD.

No client implementation or generic port is due from these server/dashboard
increments. The private API remains schema 101 with SMTP disabled; preserve the
accepted Google binding and installed phone. The dashboard serves an isolated
baseline directory documented in Platform's handoff, so local test builds cannot
replace it. Physical iPhone testing stays explicitly waived.

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
