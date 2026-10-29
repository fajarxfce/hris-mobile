# Agent continuation — HRIS Mobile

Updated 29 October 2026 (UTC). The overall HRIS PRD remains in progress. This is
the employee application generated from Compose Fluent Starter, and the proving
ground for reusable foundation changes before they are ported to Starter.

## Start here

1. Read `AGENTS.md`, inspect `git status`, the branch, recent commits and upstream.
   The working branch at this checkpoint is `feat/native-push`.
2. Read [validation](validation.md), [local-first coverage](local-first.md), the
   affected module contract and [template updates](template-updates.md).
3. Read the [Platform handoff](https://github.com/fajarxfce/hris-platform/blob/feat/firebase-auth/docs/agent-handoff.md)
   and [remaining PRD delivery](https://github.com/fajarxfce/hris-platform/blob/feat/firebase-auth/docs/completion.md#remaining-delivery-sequence).
   Backend and dashboard work is on Platform `feat/firebase-auth`, most recently
   native upload/review fixture `ee6c04a3` after schema 100 and native push recovery.
   Platform also has pushed `409abad4` (delivery observations, schema 101) and
   `f757bffd` (original payroll period comparison) on descendant feature branches,
   integrated at `b7f42070` after its one-hour retention test passed; native
   Overtime fixture `578a5031` follows that integration.
   Starter work is on `fix/push-token-lifecycle`.

The three local checkouts are siblings under `/home/fajar/hris`. A fresh clone
must be able to continue from tracked evidence; local `.work` logs, screenshots
and `/home/fajar/hris/PRD-PROGRESS.md` are supplementary. The user authorized ongoing
implementation and a push after every atomic commit. This does not authorize a
release. Preserve unrelated edits and keep implementation, tests and docs together.

## Current checkpoint: native Leave submission recovery

Platform `ccae6f8f` verifies actual Android/API/PostgreSQL/worker committed-response
loss and exact Leave replay. The unchanged `02743a9f` APK retained one unconfirmed
command through offline restart without a duplicate draft. Stable summary reload
stayed 33 → 33. One request/history/approval and one balance-version increment
reserved 0.5 days, leaving 1.5 available; six summary rows cleared and logout passed.
The final fresh fixture passed in 2.8m/3.2m with setup. The initial preview-POST
counting mistake and navigation-counter overlap are documented in
[validation](validation.md#leave-submission-response-loss-on-android).

Continue native Attendance capture pending-summary recovery next, preserving its
independent-review requirement after an explicit capture without location. Prior
capture and replica acceptance remain valid; inspect them before adding the missing
summary outcome. All owned services/markers/forwarding are removed and the dedicated
emulator stopped with data retained. No application change/Starter port applies.
FCM server credentials and Google/physical/iOS acceptance need their external inputs;
do not repeat the pending Firebase question.

## Current continuation: reconcile actual replica coverage

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
gaps without repeating completed implementation or the pending Firebase question.

## Current checkpoint: native upload and inspection review

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
due. All owned resources were cleaned and emulator app data retained. Continue
remaining PRD work after pushing: Platform is next checking actual export physical
expiry/retention with owned storage. Native Google exchange, FCM message/scoped hint
delivery and iOS/physical cases still require their separate setup. Do not repeat
the pending question for the Firebase service-account file path.

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

Continue after committing/pushing: actual FCM delivery/scoped hints, Google token
exchange and iOS/physical acceptance remain. The user rejected stopping after a
checkpoint. Platform has a pending question for the Firebase service-account file
path; none was found in the bounded workspace/config scan or relevant env vars.
Do not repeat that question while independent PRD work is available.

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
  explicit deletion. Other feature command states need their own native evidence; document download
  and upload/review cases are now accepted below.
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

Reconcile each remaining acceptance requirement with the code and retained tests
before creating another feature. Native Pending actions still needs acceptance
for other feature outcomes beyond the accepted Leave working-copy, unconfirmed
Inbox/Overtime commands, partial-download and upload/inspection-review cases. Existing per-feature delivery and lifecycle tests are not evidence for every
summary state on a device. Continue useful independent cases while provider input
is unavailable.

Firebase project `cmp-fajar` has Email/password and Google enabled. Android configs
exist locally in ignored `apps/android/google-services.json`; do not commit provider
credentials. The known remaining external work includes actual Google ID-token
acceptance, FCM service-account configuration and actual message/scoped-hint
delivery, iOS Firebase/APNs/device setup, verified HTTPS associations and physical
location/device acceptance. Recheck local availability before asking for inputs
already supplied. Public Android config is not an FCM server credential.

## Validation and device ownership

Use JDK 21 and `ANDROID_HOME=/home/fajar/Android/Sdk`. On headless Linux, root
checks use `xvfb-run -a ./gradlew --no-daemon :check`; Android development assembly
is `:apps:android:assembleDevDebug`. Select related Chromium tasks from the owning
module and inspect [development](development.md) and [distribution](distribution.md).
Do not infer device/provider acceptance from compilation or fixture responses.

Use AVD `hris-mobile-dev`, serial `emulator-5560`; never wipe its retained data.
The last acceptance used a build-specific loopback API override. Preserve the
normal environment configuration and inspect any fixture before starting another.
The accepted fixtures were stopped and private connection files removed. Phone
delivery is install-only under `AGENTS.md`: notify before installation, select its
serial explicitly and do not launch or operate its UI while it is in use.

Update this handoff and validation after each accepted increment. Include the
exact remaining work, known failed attempts and corrected causes, resource cleanup
and whether a reusable change still needs its validated Starter port.
