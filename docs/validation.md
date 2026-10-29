# Validation

## Apple CI cold compilation budget

CI run [40815794384](https://github.com/fajarxfce/hris-mobile/actions/runs/40815794384)
at `b0a95391` passed Windows (1,115 tasks, 13m1s) and Web (1,363 tasks, 16m2s).
Its Apple step reached its 15-minute limit while compiling the notification test
binary, after the location/file simulator test tasks had run. No compile error was
reported, but Apple acceptance did not complete. The previous successful Apple run
already spent 13m37s in this combined step and another 19m30s in Xcode.

The workflow now separately bounds cold test-binary compilation to 25 minutes,
unchanged simulator-test execution to 15, and unsigned simulator app build to 30,
with a 75-minute job limit and always-uploaded simulator reports. Manual `scope=apple`
selects only Apple; main/PR/default manual execution retains all four jobs. Scope
also separates concurrency groups so an Apple follow-up cannot cancel the original
full run's Linux/Android work. This changes CI only, not application behavior.

The corrected Apple run [40817592306](https://github.com/fajarxfce/hris-mobile/actions/runs/40817592306)
at `451a2c4d` is running and must pass before this reusable CI fix is ported to Starter.
The original full run remains the evidence for unchanged Windows/Web/Linux targets;
do not describe its failed Apple job as successful. Dispatch with
`gh workflow run ci.yml --ref feat/native-push -f scope=apple` and record the exact
commit and run URL. No distribution workflow is involved.

## Attendance capture response loss on Android

The unchanged `02743a9f` APK passed Android 35/API/PostgreSQL/worker acceptance
against Platform `b7f42070` artifacts/schema 101. Fixture `a837320f` creates fictional
employment and today's 09:00–17:00 roster. With location services disabled, the
native UI explicitly saves Clock in without location; the proxy drops the successful
response after the real API commits. One awaiting-confirmation count and the
original 11:03 company time survive offline cold start. Stable summary reload
remains 21 → 21, including offline reload with API forwarding removed.

Exact retry leaves one version-zero event with the original timestamps, no location,
OFFLINE/UNVERIFIED_CAPTURE, one pending review and zero accepted minutes. The local
queue and six summary rows clear while Home shows **Pending review** at the same
time. Across 30 requests there are two intended company writes. Sign-out passes.
Delivery confirmation does not approve evidence for payroll.

The case passed in 4.6m/5.2m with setup. Proxy regression, TypeScript, Biome, build
and dashboard architecture checks passed; screenshots were inspected. An OS
location-disabled dialog initially covered login; dismissing it and retrying config
after fixture readiness resolved driver setup. All owned services/containers/proxy,
markers and forwarding were cleaned, location restored and the emulator stopped
with data retained. No phone was used. The 32,853,156-byte APK retains SHA-256
`2b603fff2e40e31ee9cf5a45e694ea6aae8c96ae6c72cd4168d920c2e239c0c9`.

[Reproduction and measurements](https://github.com/fajarxfce/hris-platform/blob/a837320f/docs/native-attendance-acceptance.md)
are tracked; local `.work/attendance-native-result.json` and screenshots supplement
Git. This required no Mobile source/test rerun or Starter port. Separately, full
cross-platform CI [40815794384](https://github.com/fajarxfce/hris-mobile/actions/runs/40815794384)
was dispatched at `b0a95391` because the prior CI checkpoint predates `02743a9f`;
its result must be recorded when complete. Physical GPS/provider/iOS-device
acceptance remains distinct from these checks.

## Leave submission response loss on Android

The unchanged `02743a9f` APK passed actual Android 35/API/PostgreSQL/worker acceptance
against Platform `b7f42070` artifacts/schema 101. Fixture `ccae6f8f` creates fictional
employment, an overnight Monday shift, two opening days and an independent approver.
Android reviews and submits the first half of one day; the proxy loses the committed
response. The frozen type/date/portion/reason and one awaiting-confirmation count
survive offline cold start without double-counting their working copy.

After navigation/session admission settles, Reload summary makes no request:
33 → 33, including offline reload. An earlier measurement overlapped two late
navigation reads (31 → 33); it is not used as the stable summary observation.
API forwarding was removed during offline restart while Wi-Fi remained enabled.
Exact Retry delivery leaves one version-zero request, one history entry, one
independent pending approval, 0.5 reserved, 1.5 available and zero consumed days.
Balance version increases once. Across 43 requests there are two intended company
writes and one read-only preview POST. All six summary rows clear and logout passes.

The final fresh run passed in 2.8m/3.2m with setup. The initial run failed only the
fixture's broad POST-as-write assertion; the exact preview/submission sequence
passed after correction and a new database. Proxy regression, strict TypeScript,
Biome, production build and dashboard architecture checks passed. Screenshots were
inspected. All owned resources were cleaned; the dedicated emulator stopped with
data retained. APK bytes/hash are unchanged from the Expense case below; no Mobile
code/test rerun, Starter port or phone interaction applies.

[Tracked procedure and measurements](https://github.com/fajarxfce/hris-platform/blob/ccae6f8f/docs/native-leave-acceptance.md)
include the initial fixture limitation. Local `.work/leave-native-result.json` and
screenshots supplement Git. Delivery confirmation leaves independent approval
pending. Attendance-summary and provider/device outcomes remain separate.

## Expense save response loss on Android

The unchanged `02743a9f` loopback APK passed actual Android 35/API/PostgreSQL/worker
acceptance against Platform `b7f42070` artifacts/schema 101. Fixture `97e33310`
creates fictional employment and a receipt-optional expense category through the
public API, then loses a successful save response after the real server commits.

Before Save, one locally edited draft made zero company writes. Reloading Pending
actions left the request count at 30 → 30. The frozen save command replaced that
count with **Awaiting confirmation: 1** and no extra draft. Reload and an actual
offline force-stop/restart kept the count at 43 → 43. API forwarding was removed;
Wi-Fi remained enabled. The title, one item and Check status restored offline.

Explicit Check status replayed the same operation and payload hashes. An independent
API observer confirmed one draft at version/draft revision zero, one exact line,
IDR 25,000.25, one history entry and no submissions/payments. Across 51 native
requests there were two intended company writes. Six summary rows cleared, then
sign-out returned to login. Offline-command and confirmed-detail screenshots were
inspected. All owned containers/servers/proxy/markers/forwarding were removed and
the dedicated emulator stopped with data retained. No user's phone was used.

The fixture passed in 8.3m (8.8m with setup). The proxy regression, strict TypeScript,
Biome, production build and dashboard architecture checks passed. Both JARs from
the preceding Overtime acceptance were reused. The unchanged APK is 32,853,156 bytes,
SHA-256 `2b603fff2e40e31ee9cf5a45e694ea6aae8c96ae6c72cd4168d920c2e239c0c9`.
No Mobile code/test rerun or Starter port is needed for this fixture-only change.

[Reproduction and measured result](https://github.com/fajarxfce/hris-platform/blob/97e33310/docs/native-expense-acceptance.md)
are tracked in Platform; local `.work/expense-native-result.json` and screenshots
are supplementary. Leave-command/Attendance-summary and provider/device outcomes
retain their own acceptance scope.

## Overtime command response loss on Android

The unchanged `02743a9f` loopback APK passed against Platform `b7f42070` application
artifacts/schema 101. Platform fixture `578a5031` provisions a fictional holiday,
employment and independent overtime template, then loses the first successful
plan response after the real API commits. Android retained **Awaiting confirmation:
1** without an extra matching draft. Opening/reloading Pending actions made no
request (39 → 39), including after an offline force-stop/restart.

Opening Overtime → Plan overtime offline restored the original work date,
08:00–11:00 Asia/Jakarta interval, 15-minute break, reason and Retry. Explicit
recovery sent the same operation and payload hashes. The independent API observer
confirmed one plan at version zero, one history entry and 165 planned minutes,
without actual time. Across 54 native requests, two intended company writes were
identical. All six summary rows became empty and sign-out returned to login.

The owned Android 35 case passed in 7.7m (8.2m including setup). The proxy regression,
TypeScript, Biome, browser build and dashboard architecture checks passed. Both API
and worker JARs rebuilt in 37s. The APK hash remained
`2b603fff2e40e31ee9cf5a45e694ea6aae8c96ae6c72cd4168d920c2e239c0c9`
(32,853,156 bytes); no Mobile implementation or Starter port was needed. A transient
empty UI tree at startup required a driver retry; subsequent UI assertions passed.
Screenshots were inspected, all owned service/container/forwarding/marker resources
were removed, and the emulator stopped with its data retained. This did not use the
user's phone. No additional Mobile test-suite execution is claimed for these
unchanged sources.

[Reproduction and measured result](https://github.com/fajarxfce/hris-platform/blob/feat/firebase-auth/docs/native-overtime-acceptance.md)
are tracked in Platform. Supplementary Mobile evidence is `.work/overtime-native-result.json`
and the pending/offline/recovered screenshots. Other feature outcomes and actual
external-provider/physical-device cases remain separate.

## Interrupted upload and inspection review on Android

On 29 October 2026, Mobile `02743a9f` passed the actual Android 35/API/PostgreSQL/
worker/Garage scenario in **8.7 minutes**, **9.2 with setup**. The existing loopback
APK (32,853,156 bytes, SHA-256
`2b603fff2e40e31ee9cf5a45e694ea6aae8c96ae6c72cd4168d920c2e239c0c9`)
was reused. Platform application artifacts were `c282fa60` / schema 100;
the tracked acceptance fixture is `ee6c04a3`.

The native picker imported a fictional 3,145,841-byte PDF. The proxy dropped the
first chunk response after one MiB committed. **File transfers: 1**, the company
and original upload survived a full offline process restart with Wi-Fi/mobile data
and API forwarding disabled. Summary open/reload made no HTTP request (**32 → 32**).
Explicit Retry upload sent the same operation/bytes, then only the remaining parts.
The server retained one revision and four single-attempt chunks, matching SHA-256
`2fced5a6de049a2b5045b3c2f7c34ff2b89c76d7ce0d486a69c183b9584f760a`.

With the scanner deliberately disabled, the real worker reported
`document_scanner_not_configured`; an independent administrator stopped that job
through the normal versioned API. Check status acquired **Check failed**, and
Pending actions became **Needs review: 1** with no transfer. Reload kept the counter
at **42 → 42**. A second complete offline restart retained that review count and
file. After reconnection, explicit Retry check created the second validation job,
which followed the same controlled stop. No lifecycle row, timestamp, trigger or
verdict was rewritten, and no uninspected evidence was published.

Explicit cancellation produced **Upload cancelled**. All six summary rows became
empty before the file was removed. Confirmed local deletion and sign-out succeeded.
Independent server checks found **57 requests**, nine intended document mutations,
five chunk calls with the first pair identical, two distinct validation commands,
one cancellation, and one unpublished cancelled revision. Both offline-summary
screenshots were inspected.

The [tracked Platform fixture and complete procedure](https://github.com/fajarxfce/hris-platform/blob/feat/firebase-auth/docs/native-upload-acceptance.md)
includes finite request/connection/admission bounds and three passing loopback
regressions. Strict TypeScript now includes every native fixture; Biome, production
build and architecture/format checks passed. Mobile implementation was unchanged,
so its previously passed root/Chromium/Android checks were not repeated and no
Starter port is due. Initial config Retry resolved an app launch before fixture
readiness. Early coordinate taps during dialog/keyboard transitions did not submit;
fresh UI reads preceded the successful taps, with only one server cancellation.

All owned services, private connection files and markers were removed. Forwarding,
the public source PDF and UI dump were cleaned; connectivity was restored before
stopping the emulator with app data retained. No phone interaction occurred. Local
evidence is `.work/upload-native-result.json`, summary counter JSON, offline UI
trees and screenshots, plus Platform `upload-native-*` logs/server results. This
covers native upload/review recovery, not antivirus or every feature outcome.

## Firebase registration and offline recovery on Android

On 29 October 2026, the dedicated Android 35 emulator used the actual Firebase
Messaging SDK with the local `cmp-fajar` configuration and real HRIS API/PostgreSQL/
worker fixture. Explicit OS permission was granted in the first run. SDK token
retirement/acquisition and backend registration succeeded without a Firebase Auth
user or Google account login. The fixture never supplies a fake token to the app.

The first run found a precision bug: the server's family/registration expiry was
855 microseconds after the session boundary retained locally in milliseconds.
Successful receipt replay therefore failed metadata validation and stayed pending.
The mapper now compares at the retained precision and clamps the usable expiry to
the local boundary. It never extends local session lifetime; the next millisecond
and later expiries are rejected. Secure-state round-trip coverage preserves this.
An APK upgrade without clearing data recovered the already retained operation.

A second fixture exposed retirement of a family absent from the replacement
fixture database. The push use case now accepts only the exact
`native_session_not_found` absence result for a previously owned family on the
current account/audience. Other accounts are untouched; other errors retain the
retirement. The device-session repository still preserves the original error for
its other callers. Upgrade cleared that retained failure before new registration.

The complete final scenario passed in 9.7 minutes (10.3 with setup): the real SDK
registered a token, the API committed version 0 before the proxy dropped its
response, and enabled preference plus frozen command survived process restart
with Wi-Fi, mobile data and the API forward disabled. Reconnection and Retry sent
the identical operation/input and displayed enabled. Disabling completed without
pending state; independent SQL confirmed one registration at version 1, disabled,
with both encrypted token and token hash removed. Sign-out returned to login.
There were 51 native requests, exactly two identical PUTs, one disable POST and no
native company mutations. A first immediate UI read saw deletion still running;
bounded observation waited for its completed state without repeating the command.

`xvfb-run -a ./gradlew :check` and identity domain/data Chromium suites passed:
1,840 desktop/UI tests, 64 domain and 49 data browser tests, with no failures or
skips. Root architecture, formatting, static checks and tool suites passed. The
first browser run needed the installed `CHROME_BIN`; the corrected command passed
in 1m59s. Android assembly with the local API override passed in 18 seconds after
the retirement fix; the preceding precision build passed in 38 seconds after
setting `ANDROID_HOME`. The final APK is 32,853,156 bytes, SHA-256
`2b603fff2e40e31ee9cf5a45e694ea6aae8c96ae6c72cd4168d920c2e239c0c9`.

The [tracked Platform procedure](https://github.com/fajarxfce/hris-platform/blob/feat/firebase-auth/docs/native-push-acceptance.md)
contains setup, driver steps, limits and independent assertions. Both loopback
proxy regressions passed; fixture TypeScript/production build and Platform
architecture/format checks passed. Owned services, connection/control files and
ADB forwarding were removed. The emulator stopped with data retained and
connectivity restored. Offline/disabled screenshots were inspected. Only the
emulator was connected; no physical phone or iOS installation is claimed.

Local supplementary evidence: `.work/push-native-final-result.json`,
`.work/push-native-validation-counts.json`, `.work/push-native-recovery-check.log`,
`.work/push-native-recovery-build.log`, and `.work/push-native-final-*.png`.
Platform retains `.work/push-native-fixture.log` and its sanitized server result.
The initial fixture timed out while the precision issue was being fixed; that
initial run is not counted as a passing full acceptance.

FCM message delivery, scoped foreground/background provider hints, Google token
exchange and iOS/APNs remain separate. Worker service-account credentials are
still unavailable. These fixes interpret HRIS registration metadata and retained
HRIS family cleanup; the generic SDK adapters are unchanged and need no Starter
port.

## Partial-download recovery on Android

On 29 October 2026, the unchanged application at `c650e0d7` passed a new actual
API/PostgreSQL/worker/Garage/Android 35 workflow. The source had 3,145,841 bytes,
uploaded through the API in four parts; accepted inspection metadata was seeded
under normal database constraints. This case tests transfer recovery, not scanning.
The [tracked Platform fixture and exact steps](https://github.com/fajarxfce/hris-platform/blob/feat/firebase-auth/docs/native-download-acceptance.md)
are committed in Platform `f23b73ba`; its application artifacts were `adfb7b32`
(schema 99).

The proxy allowed one 1,048,576-byte range before returning 503 for the next range
and later native requests. The detail showed **Download incomplete** and Pending
actions showed **File transfers: 1**. Opening/reloading the summary made no server
request (31 → 31). Removing forwarding and force-stopping the app preserved the
company, pending transfer and local incomplete-file route through offline startup.

After explicit reconnection and Resume download, the app started at byte 1,048,576
and acquired only the three remaining ranges. Native Save a copy wrote
`pending-transfer.pdf`; its 3,145,841 bytes matched SHA-256
`51c7b10f2eb0a8897a8507cca1be050125a585e53f7aa93a75a54ba684931fb9`.
The UI showed **Copy saved.**, all six summary rows became empty and sign-out
succeeded. The independent proxy observed 49 requests, four exact successful
ranges with matching hashes and zero native company mutations.

The manual fixture passed in 9.7 minutes (10.2 including setup). It reused the
previously built 32,852,906-byte loopback APK with SHA-256
`70ea4121020fcd9092c9d587fc2c99faee26c2230b54a6bbfa86706f15c5008f`;
no new application build or root-test execution is claimed. Platform's new
loopback regression checks interruption/recovery, competing admission bounds and
cleanup during a pending stream. Biome, TypeScript, production build, architecture
and formatting passed. No application source or reusable foundation changed, so
there is no Starter port.

Offline-summary and saved-copy screenshots were inspected. The early application
launch required explicit authentication-configuration Retry; one UI automation
read was retried after cold startup completed. No app fix was needed. Owned
services, connection/control files, forwarding and the created public copy were
removed. The emulator stopped without wiping app data; the user's phone was not
used.

Local evidence: `.work/download-native-result.json`, before/after counter JSON,
and `download-native-incomplete.png`, `download-native-offline-summary.png`,
`download-native-offline-detail.png`, `download-native-saved.png`,
`download-native-recovered-summary.png` and `download-native-signed-out.png`.
Platform retains `.work/download-native-fixture.log`, server-result/range metadata
and proxy/architecture/lint/type-check logs. This adds a real partial-transfer
summary case; upload/review and other feature outcomes retain separate acceptance.


## Inbox command response loss on Android

On 29 October 2026, Mobile `9dbaf730` built the dev APK with Java 21 and
`-PapiBaseUrl.dev=http://127.0.0.1:18082/api/v1/` in 45 seconds. The dedicated
Android 35 emulator completed the real API/PostgreSQL/worker
[command recovery workflow](pending-actions.md#android-recovery-after-a-committed-command-loses-its-response).
One server read command committed before its response was dropped. The device
retained an unconfirmed command and Pending actions count through offline process
restart. Summary open/reload made no HTTP requests (30 → 30). Reconnection and
Retry reused the original operation/input, returned all six summary rows to empty
and left server version 1 with acknowledgment absent. Sign-out succeeded.

The independent Platform fixture passed in 6.0 minutes (6.4 including setup),
observing 42 native requests and exactly two identical company read commands.
TypeScript, Biome and browser production build passed for its new fixture. Owned
services and connection files were removed, forwarding removed and the emulator
stopped with data retained. The initial emulator System UI warning and driver
activity/nested-navigation targets were resolved before completing the workflow.
No Mobile application code or shared foundation changed; existing root/browser
application suites were not repeated for acceptance-only documentation.

Local evidence: `.work/pending-command-assembly.log`,
`.work/inbox-command-native-result.json`, `.work/inbox-command-counter-after.json`,
`.work/inbox-command-unconfirmed.png`, `.work/inbox-command-pending.png`,
`.work/inbox-command-offline-pending.png`, `.work/inbox-command-offline-detail.png`,
`.work/inbox-command-recovered-summary.png` and `.work/inbox-command-signed-out.png`.
Offline/recovered screenshots were inspected. Platform keeps its independent
result/request hashes under `.work/inbox-native-*` and the runnable fixture in Git.
This did not exercise real FCM delivery, iOS or the user's phone and needs no
Starter port.

## Android URL navigation

On 28 October 2026, Mobile `1c1f9104` and Platform `893db26a` passed the
[real API/Android URL workflow](deep-links.md#android-url-acceptance) on the dedicated
Android 35 emulator. The same successfully built dev APK used for Pending actions
was installed; these documentation commits change no application code.

Actual browsable `VIEW` intents covered cold pre-login entry, explicit company
selection, repeated message navigation with bounded Back behavior, another account
or company, unexpected query parameters, background/resume, Inbox/Account/Pending
actions/Data and sync, and an offline cold launch. An independent native API
session verified unchanged read/acknowledgement timestamps. After an administrator
archived the message, a successful refresh removed its contents; an offline cold
launch and failed refresh kept the archived message unavailable. The proxy
observed 33 requests, no request for the other company and no native company write.

The bounded fixture passed in 3.4 minutes (3.9 including setup). Local evidence is
`.work/link-native-result.json`, `.work/link-native-company-selection.png`,
`.work/link-native-message.png`, `.work/link-native-rejected-scope.png`,
`.work/link-native-inbox-unread.png`, `.work/link-native-offline-message.png`,
`.work/link-native-archived.png` and `.work/link-native-archived-offline.png`.
The message and archived offline screenshots were inspected. Platform retains
the independent API result and sanitized request evidence in `.work/link-native-*`.
Sign-out succeeded. The owned database, API, worker, mail fixture, proxy and
emulator were stopped and connection metadata removed; emulator data was retained.
Provider push, verified HTTPS associations, iOS and physical devices were not
exercised. There is no new reusable implementation to port to the starter.

## Pending actions on Android

On 28 October 2026, the dev APK at `9a557dcc` built successfully with Java 21 and
the local Android SDK, then ran on the dedicated Android 35 `emulator-5560`.
Only this build used the loopback API override. Platform `1f95e0c3` supplied an
isolated API/PostgreSQL/worker fixture with a fictional member, employment, leave
policy, balance and reviewer. No Firebase user or physical phone was involved.

The [Pending actions workflow](pending-actions.md#android-acceptance-with-the-real-api)
passed: pristine form exclusion, one edited draft, summary open/reload without
HTTP (44 requests before and after), offline process restoration, unchanged input
after online retry, explicit deletion and sign-out. The proxy observed 53 native
requests in total, with no company write. The fixture's independent API assertion
passed; its bounded browser session finished in 5.6 minutes (6.0 including setup).
Two early UI-driver lookups hit transient/stale layouts; they were corrected and
the edited-draft observations were repeated before recording these results.

Local evidence is in `.work/pending-native-result.json`,
`.work/pending-native-assembly.log`, `.work/pending-native-pristine.png`,
`.work/pending-native-summary.png`, `.work/pending-native-offline-summary.png`,
`.work/pending-native-offline-draft.png`, `.work/pending-native-restored-online.png`
and `.work/pending-native-deleted.png`. The summary and offline form screenshots
were inspected. Platform retains the sanitized counter and fixture result under
its own `.work/pending-native-*` files. The owned database, API, worker, mail
fixture, proxy and emulator were stopped, and temporary connection metadata was
removed. Emulator data was preserved. This documentation increment changes no
application behavior; no new starter port is needed.

## Real Firebase Android acceptance

On 28 October 2026, Mobile `d191c61d` ran on the dedicated Android 35 Google APIs
emulator `emulator-5560`. A temporary email/password user in `cmp-fajar` was bound
only to an isolated local HRIS API/PostgreSQL fixture at platform `8305f435`.
Firebase password authentication, Google's public-key verification and native HRIS
issuance succeeded with the production adapters. `/me` correctly reported pending
MFA and no assurance before verification.

The Android UI completed password login, authenticator enrollment, verification and
recovery-code acknowledgement, then reached company selection. The fixture account
had no company assignment, and the application correctly showed no company access.
Sign-out returned to ready login. Google launched the system account sign-in UI;
Back dismissed it and restored ready login without an error or stuck submission.
No Google account was added, so Google token issuance/exchange and signing-certificate
registration are not yet accepted. No physical phone or iOS acceptance is claimed.

The temporary Firebase account was deleted successfully. The owned API/database and
emulator were stopped, temporary credentials and MFA dumps were removed, and the
configured API override was limited to this local build. Sanitized evidence is in
`.work/firebase-live-result.json`, `.work/firebase-emulator-result.json`,
`.work/firebase-emulator-mfa.png` and `.work/firebase-emulator-company.png`.

Remote [run 40643807959](https://github.com/fajarxfce/hris-mobile/actions/runs/40643807959)
for exchange core `67c455e4` passed Linux/Android, Web, Windows and Apple, including
the Xcode simulator build. Provider/UI `d191c61d` also passed Linux/Android, Web,
Windows and Apple simulator tests/full Xcode simulator build in
[run 40647201802](https://github.com/fajarxfce/hris-mobile/actions/runs/40647201802).
This confirms cross-platform build/container compatibility; Google token issuance
and iOS Firebase provider acceptance remain separate from those checks.

## Firebase provider and login UI

On 28 October 2026, root checks passed with 1,837 desktop/UI tests, runner and
distribution suites, architecture, formatting and static analysis. Chromium passed
62 identity-domain, 48 identity-data and nine auth-presentation tests (119 total).
Configured Android dev assembly passed. Output is in
`.work/firebase-login-final-validation.log`.

The 25 additional regressions cover provider discovery, Firebase's password/Google
protocol, bounded and sanitized responses, cancellation and late callbacks, nonce
and audience binding, one-shot interaction cleanup, session-free provider results,
stable retry intent, login disposal and changing configuration. Two real-container
tests traverse login MVI, Firebase REST, native exchange and session storage; they
also verify that mismatched projects produce no provider or native-password I/O.
Provider requests in these tests use a mock HTTP engine; real provider/device
acceptance remains separate.

Android uses Credential Manager 1.6.0 and Google ID 1.2.1. Verification metadata was
generated by Gradle, and the six direct AAR/module/POM artifacts were independently
matched against official Google Maven SHA-256 values. The local provenance record
is `.work/firebase-dependency-provenance.json`.

## Firebase native session exchange

On 28 October 2026, root checks passed with 1,812 desktop/UI tests, the runner and
distribution suites, architecture, formatting and static analysis. Identity
Chromium tests passed all 58 domain and 35 data cases; configured Android dev
assembly also passed. Output is in `.work/firebase-client-exchange-final.log`.

Nine coordinator regressions cover both providers, matching installed/backend
projects, unavailable methods, bounded input, preserved failures, explicit retry,
session replacement at each acquisition boundary, changed proof, expired native
credentials, concurrent sign-ins, timeout, cancellation and late results. Six
HTTP adapter regressions verify the public endpoint, unchanged idempotency/payload,
bounded config/credentials, additive providers, sanitized errors and cancelled I/O.
Only HRIS credentials reach the session store.

This is the [client exchange core](firebase-auth.md). Provider I/O and login UI
are now wired and separately validated above; no real Firebase authentication is
claimed by these signed-backend/mock-client fixtures. The HRIS native API policy
is application specific and is not a starter port.

## Firebase provider configuration

On 28 October 2026 the user enabled Email/password and Google in `cmp-fajar`.
The public Firebase configuration request returned HTTP 200. A supported
`accounts:createAuthUri` request for Google returned its public web client ID;
the authorization URL was not opened and no user was signed in or created.
That ID was added to the ignored, mode-0600 Android configuration for all three
HRIS flavors. Dev, staging and prod Google Services processing passed in
`.work/firebase-google-client-validation.log`. This resolves the earlier
`CONFIGURATION_NOT_FOUND` setup dependency. It does not prove signing-certificate
registration, Google account selection or Firebase-to-HRIS login on a device.

The backend Firebase exchange and its acceptance evidence are tracked in
[the platform contract](https://github.com/fajarxfce/hris-platform/blob/feat/firebase-auth/docs/firebase-auth.md).
Mobile provider and login wiring are implemented; real provider acceptance remains
active work.

## Native worker registration metadata

Remote [run 40637280472](https://github.com/fajarxfce/hris-mobile/actions/runs/40637280472)
for Inbox hints `ea2c58b7` passed Linux/Android, Web and Windows. Apple passed its
simulator test step but failed framework linking during Xcode build because Koin
annotations 2.3.1 emitted the same public `SyncTask` metadata property in the app
and dependent modules. The application now composes its two worker entry points
through its own Koin DSL module, preserving their singleton aliases and deferred
SDK acquisition. Feature use-case registrations remain in their owning modules.

The app's generated metadata no longer exports the conflicting property. Root
checks passed with 1,797 desktop/UI tests, all 19 app Chromium tests and configured
Android dev assembly in `.work/native-worker-binding-validation.log`. The container
regression verifies unique task keys and identical instances through direct and
scheduler lookup. Remote [run 40640556690](https://github.com/fajarxfce/hris-mobile/actions/runs/40640556690)
for `23d17e77` passed Web, Windows, Apple simulator tests and the complete Xcode
simulator build. This verifies the native metadata fix. Linux stopped at a comment
formatting check; `7f0bdae7` fixes that comment and passed local format checks.
The original native failure is recorded in `.work/ci-inbox-apple.log`.

## Background native registration recovery

On 28 October 2026, root checks passed with 1,797 desktop/UI tests, runner and
distribution suites, architecture, formatting and static analysis. Chromium passed
49 identity-domain tests, 29 identity-data tests, nine notification-presentation
tests and 19 app tests. Configured Android dev assembly passed. Output is in
`.work/native-push-background-validation.log`; the final host check and formatting
also passed in `.work/native-push-background-final.log`.

Six policy regressions cover empty/unsupported hosts, token rotation and expiry,
persistent retry bounds, cancellation/receipt recovery, concurrent foreground
disable and failed attempt persistence. Storage verifies legacy record defaults,
restart and invalid retry metadata. Two portable worker tests cover stalled/failed
identity reads, deadline, cancellation, late emission and subscription disposal.
Updated MVI tests verify that Settings never keeps an SDK token observer and still
handles resume, response loss, disable and session replacement. The real container
resolves and invokes the registered worker.

Android callbacks/periodic work and Apple foreground/BGAppRefresh invoke the
shared task. Apple prioritizes registration before other background acquisition;
its native host changes require remote execution. This remains automated fixture
and Android assembly evidence, without real Firebase/APNs or physical-device
acceptance. Firebase Authentication exchange is separate work.

## Authorized native Inbox hints

On 28 October 2026, root checks passed with 1,788 desktop/UI tests, runner and
distribution suites, architecture, formatting and static analysis. Inbox domain
passed 77 Chromium tests, Inbox data 11, notification data eight and app links 17.
Configured Android dev assembly passed. Logs are
`.work/inbox-push-validation-2.log` and `.work/inbox-push-final-validation.log`.

Eight queue, nine delivery and three storage regressions cover strict hint parsing,
wrong accounts, duplicate reception, retained attempt evidence across accounts,
capacity/expiry, competing writes, failed scheduling, cancellation, bounded
preparations, canonical acquisition, failed denial cleanup, disabled push and
uncertain OS delivery. The final preference-read regression failed before adding
the post-read session/deadline/admission check and passed afterward.

Two real-container/Ktor/SQLite tests receive a hint, synchronize the encrypted Inbox,
resolve the generated resource link, and recover from offline/confirmed-MFA failure
without sending acknowledgement commands. DI tests verify task registry construction
without prematurely acquiring an OS adapter and explicitly resolve the display path
with native adapters supplied. Android/iOS receiver wiring is present; this Linux
validation does not execute Swift, APNs, FCM delivery or device behavior. Background
registration renewal and Firebase sign-in remain separate work.

## Hierarchical notification destinations

The notification adapter now validates bounded internal path segments before OS
I/O. Android appends each segment separately, preserving resource links instead
of encoding their slashes into one segment. Notification replacement uses the full
delivery ID as its tag and only alerts once for a replacement.

Two portable regressions cover hierarchical paths and rejection of schemes,
queries, fragments, encoded separators, controls, empty segments and oversized
paths. On 28 October 2026, root checks passed with 1,787 desktop/UI tests,
notification data passed all eight Chromium tests, and configured Android dev
assembly passed in `.work/inbox-push-validation-2.log`. These totals also include
Inbox work validated alongside the adapter; the portable change alone does not
implement HRIS authorization or provider delivery.

## Independent web packaging in CI

Remote [run 40631137228](https://github.com/fajarxfce/hris-mobile/actions/runs/40631137228)
for registration core `f98a7884` passed Apple simulator/Xcode and Windows checks.
The Linux runner shut down during `compileProductionExecutableKotlinWasmJsOptimize`,
after the test and Android assembly tasks, leaving that job incomplete. Web
distribution and its artifact now have a separate job from Linux verification and
Android packaging. Remote [run 40634564866](https://github.com/fajarxfce/hris-mobile/actions/runs/40634564866)
for `5877767b` passed all four jobs: Linux verification/Android, Web distribution,
Windows and Apple simulator/Xcode. Later hint processing and background worker
changes have their own local validation and subsequent remote acceptance.

## Native push Settings and host lifetime

On 28 October 2026, root checks passed with 1,764 desktop/UI tests, runner and
distribution checks, architecture, formatting and static analysis. All 71 identity
domain/data and nine notification-presentation Chromium tests passed. Android dev
assembly passed with the supplied Firebase configuration. Output is in
`.work/native-push-ui-validation-2.log`.

Seven MVI regressions cover explicit permission, token rotation, stop/resume,
late permission, response loss, later disable, replacement accounts during transport,
identity-read recovery, expired credentials and disposal. The secure-state observer
is tested through committed writes, competing writes, cancellation and reopening.
The application container resolves the mounted coordinator. Two Settings UI tests
cover explicit switch actions, unavailable platforms, busy controls, localized errors
and reachable retry with fixed Back navigation at 200% Indonesian text. The dark
layout screenshot was inspected. Real provider delivery/login and device acceptance
remain open; this increment does not implement FCM Inbox hint processing.

## HRIS native push registration core

On 28 October 2026, root checks passed with 1,754 desktop/UI tests, runner and
distribution tests, architecture, formatting and static analysis. All 70 identity
domain/data Chromium tests passed. Android dev assembly passed with the supplied
Firebase configuration, as did staging/prod Google Services processing. Logs are
`.work/native-push-core-validation-2.log`, `.work/native-push-core-final.log` and
`.work/firebase-configuration-check.log`.

The [registration core](native-push.md) has transport, secure-storage and policy
coverage for exact frozen replay, response loss, later disable, malformed metadata,
concurrent storage transitions, failed deletion, permission revocation, account
replacement, cancellation and late responses. A real-container test resolves its
DI graph and verifies unsupported desktop avoids provider I/O. Settings/lifecycle
integration and real provider acceptance remain separate work.

## Wrapped attendance captions

The attendance card now reserves localized date and schedule text using its actual
font and available width. Loading, work, overnight, off and unassigned schedules
keep the following menu in place, including Indonesian at 200% text and 280 dp.
Hidden measuring text is excluded from accessibility. All three layout regressions
passed locally; the narrow case failed before the fix. This repairs the layout
failure observed in Mobile CI run `40627088767`; that run's Windows and Apple
jobs passed, including the two provider-token Apple simulator regressions.

## Provider token retirement

On 28 October 2026, root checks passed with 1,735 desktop tests, plus the runner,
distribution, architecture and format checks. All six notification-data Chromium
tests and Android dev assembly passed. The run used JDK 21, SDK 36 and Node
22.23.3; output is in `.work/push-token-validation.log`.

The reusable adapter now supports SDK token deletion without requesting permission
or acquiring a replacement. Regression coverage reproduces old callback replay
after retirement and verifies current-token acquisition, bounded callback
conflation, cancellation, late results, failed deletion and explicit retry. The
same source/tests are ported to the starter after the Mobile checks.

Two Apple bridge regressions and their simulator CI task were added. They were
not executed on this Linux host; remote Apple validation is recorded separately
when it finishes. Provider configuration files were absent from the Android/iOS
runners. No real Firebase/APNs registration, delivery or device action is claimed
by this adapter increment. Backend registration ownership remains separate work.

## Scoped HRIS links and resume behavior

On 28 October 2026, root checks passed with 1,732 desktop tests, ten
Android-runner tests and six distribution tests, plus architecture/format checks.
The application Chromium suite passed 17 tests; Android dev assembly passed.
The run used JDK 21, SDK 36 and Node 22.23.3; output is in
`.work/hris-links-validation-2.log`.

The assembled application tests exercise an Inbox resource URL before login,
first company selection, account/company mismatch, duplicate links, bounded Back
navigation, host background/resume, denial and admitted recovery. Opening a link
does not send read/acknowledgement commands. Parser and ownership tests also cover
malformed inputs, session replacement, stale acknowledgements and expired local
admission. See [deep links](deep-links.md) for the supported paths and boundaries.

The UI fixture now observes lifecycle state during composition so background
transitions really reach the host. This exposed an expense detail scroll reset:
temporary loading rows clamped the retained financial list to the top. A separate
feedback scroll state now preserves the selected activity position while content
is cleared and restored through local admission. The full offline expense UI
regression passes with real stop/resume transitions.

Generic session ownership and resumed-destination navigation were ported to
starter `29081c5` and validated there independently. No live HRIS API, emulator,
phone installation, iOS runtime, push-provider delivery or remote CI was exercised
for this iteration. These checks complete the automated URL-routing increment;
native push registration and the rest of the product PRD remain open.

## Pending actions and UI recovery

On 28 October 2026, `xvfb-run -a ./gradlew :check` passed with 1,724 desktop
tests and four build-convention tests using JDK 21 and Android SDK 36. Coverage
includes the Account pending-actions route, all six DI bindings, returning to an
edited leave draft without submission, and reloading the summary without HTTP.
Narrow layouts retain accessible actions at 200% text; Indonesian denial copy
offers verification without displaying a false empty count.

The domain tests cover draft/command deduplication, unfinished expense items,
document transfer/validation states, expired authority, failed denial persistence,
session replacement during a delayed read, cancellation, disposal and explicit
local retry. Encrypted draft contracts cover bounded metadata enumeration and
reauthentication. Chromium passed 253 tests: database 25, leave data 46, workforce
data 55, expense data 49 and document domain 78, using Node 22.23.3.
Application Web compilation (`:apps:shared:compileKotlinWasmJs`) and standard
Android dev assembly (`:apps:android:assembleDevDebug`) also passed.

The recovered leave UI regression waits for catalog loading and enabled refresh
before opening dates, then selects a day within the dialog. A separate weekday
layout regression reserves the schedule row while attendance loads and checks
work/off/unassigned transitions at normal English and 200% Indonesian text.

The reusable database source/tests match starter `8707dec`; starter root checks,
its 25 database Chromium tests and Android dev assembly passed separately. These
checks did not install or operate a phone/emulator, execute iOS, exercise a live
HRIS API, publish a PR or trigger remote CI. Local logs are
`.work/pending-feature-check-3.log`, `.work/pending-ui-browser-check.log` and
`.work/pending-targets.log`.

## Month labels at large text sizes

On 16 October 2026, root checks passed with 1,536 desktop tests and four convention
tests. Standard Android dev assembly and an isolated loopback assembly passed.
The shared UI regression verifies the full month and year at 320 dp width and
200% text, with adjacent 48 dp actions and one click callback.

An API 35 emulator upgraded without clearing application data while offline.
Home and September attendance reopened locally. At 200% text, the inspected
screen displayed September and 2026 on separate complete lines. All ten Workforce
records and both active generations were unchanged after the upgrade and
navigation. The Android crash buffer was empty. Emulator font and connectivity
were restored before shutdown.

The validated standard WireGuard APK was restored byte-for-byte with SHA-256
`81bf986877acd00e28cce9beb53054596b6c629ef3671e66373abe95740611b0`.
No phone installation was performed for this check.

## Android acceptance with closed attendance adjustments

On 16 October 2026, an API 35 emulator used an isolated API/PostgreSQL/worker with
fictional accounts. The public workflows created and closed September attendance,
then received two late offline captures. Independent evidence decisions and an
overnight correction recorded 23:00–05:00 with a 60-minute break. Mobile history
changed from one present day and seven hours to two present days and twelve hours.
The original closing response remained unchanged.

With airplane mode enabled, Wi-Fi/mobile data disabled and API forwarding removed,
a force-stop and restart reopened Home and monthly history. The corrected five-hour
day remained readable while the server independently recorded a later absence.
All ten retained Workforce ciphertexts and both active generations were unchanged
after offline navigation; no replacement generation was pending.

Reconnection and refresh displayed one present day, one recorded absence and seven
hours. The corrected canonical day advanced from version four to five. Directory
metadata refreshed independently; the other eight stored records, including the
original worked day and the roster, retained their hashes. The original closing
response still matched its initial value. The inspected Android crash buffer was
empty. These data checks used application source `13e196a`; a separate large-text
month-label correction follows from the layout inspection.

The isolated loopback APK was not installed on the phone. Its standard WireGuard
counterpart was restored byte-for-byte with SHA-256
`b41f060c94684da8d7b0f5e935417e4a944b45a5432374c158c70ae9c826928c`.
Physical location acceptance and the staged development rollout remain separate.

## Carried leave balances

On 16 October 2026, the workspace root checks and Android dev assembly passed.
Leave passed 89 domain, 65 data and 59 presentation desktop tests; all 163 assembled
UI tests passed. Chromium passed 200 targeted cases across Leave, localization and
app integration. Production Web distribution passed with its existing three bundle
warnings.

The new coverage validates carry conservation, account subsets, original UTC
boundaries, absent metadata and malformed-response mapping. A record-format upgrade
keeps the published balances while unchanged ledger versions acquire carry data;
failure, cancellation and a real SQLite/encrypted-store restart retain the staged
replacement. Publication remains atomic and does not restart bootstrap repeatedly.

At 360 pixels and 200% text, Indonesian carry amounts and the original company date
fit the balance card. Fixed navigation and submission controls remain reachable.
The screenshot was inspected. An existing profile MFA workflow test initially
timed out on a simulated mouse click. It now reveals the visible action and invokes
its accessibility click before checking renewed verification and restored private
fields; authentication implementation and workflow assertions are unchanged.

The standard WireGuard APK was installed on the authorized Android phone with
`--install-only`. Its installed SHA-256 matched the validated 29,773,936-byte file:
`ab8be6412ff47a1f3284aaee767ea1e4eee07815136e4290addaa350220972d9`.
The package recorded its update at 07:14 on 16 October 2026. No phone UI was opened.
This increment's UI and upgrade acceptance used automated tests; installation alone
does not establish a new device interaction or performance measurement.

## Android carried-balance upgrade acceptance

On 16 October 2026, an API 35 emulator used an isolated API, PostgreSQL and worker
with fictional accounts. Public policy, balance-adjustment and year-closing APIs
created 3.5 carried days with a 31 December 2026 deadline in Asia/Jakarta. A separate
ordinary adjustment made the total 5.5 days. Removing the policy's optional deadline
after closing preserved the award's original date and UTC boundary. The employee's
own balance API returned those same facts.

The preceding Android APK acquired format-2 records. With API forwarding removed,
an in-place upgrade opened the retained 5.5-day balance. Three stored ciphertexts
and the published generation were unchanged. Reconnecting and explicitly refreshing
published the carry metadata under a new generation with the same record IDs and
ledger versions. Another cold start without API access retained the new ciphertexts,
total and original deadline. The failed refresh remained visible alongside the facts.

At 200% Android text, the English carry amount and company-local date wrapped within
the card, with fixed back and submission controls still reachable. The scrolled
screenshot was inspected; the emulator crash buffer contained no HRIS crashes.
The fixture processes, database container and emulator were stopped, preserving
AVD data. The temporary loopback APK was never installed on the phone; the standard
WireGuard build output was restored byte-for-byte.

Root checks and Android assembly passed again, including 163 assembled UI tests.
Two high-level flow tests now invoke visible accessibility actions after their
content is revealed; module isolation and offline profile assertions are retained.
This run does not measure rendering performance or exercise a real expiry deadline.

## Android acceptance with published amendments

On 16 October 2026, an API 35 emulator used an isolated API, PostgreSQL and worker
with fictional accounts. The production workflows calculated, independently
approved and published a September statement, confirmed its payment, then approved
and published a correction and recorded the resulting returns. The continuation
browser scenario passed; no payroll rows were inserted directly.

The Android application acquired revision 0 before publication of revision 1.
Its displayed take-home pay advanced from IDR 9,670,480.75 to IDR 8,800,320.75.
Selecting revision 0 restored the original statement while retaining the current
payment balance: IDR 9,670,480.75 transferred, IDR 870,160 returned and no remaining
payment or overpayment. Both exact-revision API responses remained unchanged.

With Wi-Fi, mobile data and the emulator's API tunnel disabled, a force-stop and
restart reopened Home, the annual list and current detail from storage. Previous
and Latest navigation worked offline. All five encrypted payroll records and both
active generations were unchanged after restart, revision selection and subsequent
reconnection. Connection feedback cleared after an admitted refresh. At 200% text,
fixed revision controls remained reachable and amount rows retained their complete
fractional digits. Screenshots were inspected; the crash buffer was empty.

The Android system save dialog exported revision 0 in English after revision 1
had already been published, and revision 1 in Indonesian. Both saved files matched
the exact server response size and SHA-256:

| Statement | Bytes | SHA-256 |
| --- | --- | --- |
| Revision 0, English | 11,033 | `eab6e27a69b6dd4fee75a05ea13393fc46f86d621b2b58449b1b5ea6ff515628` |
| Revision 1, Indonesian | 11,107 | `727072b81e0c980ea75a7c648ef84616671f7ec9a16e140d70572f2f70513671` |

Application source was unchanged. A temporary dev APK used the isolated loopback
API override; the standard WireGuard APK was restored with its validated SHA-256
`55b5aafa73dddcf387b1f5015e2069861e3ba6db62b642b5e5e9a140eacce3cf`.
No new phone installation was needed. Font scale and connectivity were restored,
the emulator stopped with its data retained, and the isolated services shut down.
These checks cover Android behavior; they do not establish iOS device acceptance
or frame-time performance.

## Retained payslip revisions

On 15 October 2026, root checks passed with 1,527 desktop/JVM/UI and convention
plugin tests. Android dev assembly and production Web distribution passed.
Payroll passed 58 domain, 42 data and 25 presentation desktop tests. Targeted
Chromium suites passed 136 tests across payroll, localization and app integration,
including the IndexedDB history contracts. The Web bundle retains its three
existing bundler warnings.

Coverage includes exact-revision acquisition, immutable archive records, retained
pre-upgrade statements, current payment progress while reading older statements,
parent deletion, account replacement, cancellation and bounded acquisition.
Confirmed denials stay gated through failed cleanup and retries; history reads
cannot clear them or advance the feed cursor. SQLite and IndexedDB checks cover
database reopening, concurrent/stale writes, authenticated ownership, missing keys
and temporary-buffer cleanup. DI checks verify one replica/history repository and
content-key owner.

Assembled Compose checks navigate already acquired revisions offline, retain the
selected statement through a newer payment balance and save a PDF pinned to that
revision. At 200% text, fixed navigation remains reachable and payment amounts
retain their complete fractional digits on one line. Screenshots were inspected.
These tests use controlled API responses; they do not establish frame-time
performance or actual-server acceptance of nonzero revisions.

API 35 emulator acceptance upgraded the existing installation with Wi-Fi and
mobile data disabled. Home, the annual list and the September payslip reopened
from storage. All three encrypted payroll record hashes and the active generation
were unchanged. Reconnection cleared connection feedback and retained the same
statement and balance. Screenshots were inspected, the crash buffer had no fatal
exception, and the emulator was stopped with connectivity and application data
retained. This development fixture contains revision zero; historical navigation
was exercised by the automated tests above.

The validated dev APK is 30,303,300 bytes, with SHA-256
`55b5aafa73dddcf387b1f5015e2069861e3ba6db62b642b5e5e9a140eacce3cf`.
On 15 October 2026, this APK was installed on the authorized Android 16 phone
with `--skip-build --install-only`. All 45 recorded source inputs and the artifact
matched the validated manifest before installation. ADB reported success; the
application was not launched and no phone UI was operated. Subsequent actual-server
acceptance of nonzero revisions is recorded above.

## Revised payslips and payment balances

On 15 October 2026, root checks passed with 1,498 desktop/JVM/UI tests, Android
dev assembly and production Web distribution. Payroll passed 43 domain, 35 data
and 19 presentation desktop tests. Targeted Chromium suites passed 108 tests
across payroll, localization and app integration. The Web bundle retains its
three existing bundler warnings.

Coverage includes exact decimal settlement, incomplete current responses, legacy
records, canonical revisions newer or older than change references, interrupted
acquisition-format replacement and checkpoint resumption. SQLite and IndexedDB
contracts retain the preceding generation until complete publication. Export
tests freeze the displayed revision through background updates, conditional PDF
requests, cancellation and Activity stops. Assembled UI checks exercise an
independently newer balance and 200% text.

API 35 emulator acceptance upgraded the existing installation while Wi-Fi and
mobile data were disabled. Home, the annual list and the September payslip reopened
from storage. Legacy payment status remained visible with no invented balance;
all three encrypted payroll records and the active generation were unchanged.
Reconnection to the development API published the replacement generation and its
current balance. A subsequent offline force-stop and restart retained that balance
and all three new record hashes. The real fixture used assessment revision zero;
amended revisions were exercised by automated tests.

The Android system save dialog completed an Indonesian PDF export with `v0` in
its filename. The saved 11,105-byte file had a valid PDF header. Screenshots were
inspected, the crash buffer was empty, connectivity was restored and the emulator
was stopped with its application data retained.

The validated dev APK is 30,094,749 bytes, with SHA-256
`a4550ad8c1c3c1bedc1d45723c07c5822067ad87d1321fb7be471eb472ca0a08`.
Phone delivery is pending: the explicit connection attempt to the authorized ADB
endpoint timed out. This APK has not been installed on the phone.

## Private-profile APK delivery

On 14 October 2026, the validated 30,229,893-byte dev APK was installed on the
authorized Android 16 phone with `--skip-build --install-only`. ADB reported
success and package metadata recorded the update at 21:13:23 WIB. SHA-256:
`8bdc9ed12491f8219849981b388f9efa26aa6cd739b8ee9836a67a3b4233488f`.
The existing application data was retained; the phone UI was not opened or driven.
Profile and offline acceptance used the dedicated emulator, as recorded in
[People](people.md#private-profiles).

The first VPN transfer exceeded the 180-second installer deadline. Package
metadata still showed the preceding installation. A subsequent explicit attempt
with a bounded 600-second deadline succeeded. The runner now accepts 30–900
seconds, preserves the default, rejects invalid values before device discovery,
and reports timeout as unconfirmed without retry or launch. All ten runner tests
passed in this repository and Compose Fluent Starter. Application code and the
validated APK were unchanged by this tooling correction.


## Payslip replica

Root checks passed with 1,320 desktop/JVM/UI tests, Android dev assembly and
production Web distribution. Payroll passed 35 domain, 26 data and 18 presentation
desktop tests, plus 35 domain, 20 data and 18 presentation Chromium tests. The Web
bundle retains its three existing bundler warnings.

Tests cover local annual paging and independent details, bounded change-feed
acquisition, payment versions, tombstones, staged cursor replacement and atomic
publication. They exercise concurrent triggers, cancellation, late ownership
changes, failed hydration, persisted denials, failed cleanup and a newer denial
during admission. SQLite and IndexedDB contracts verify indexed query bounds,
database reopening, authenticated metadata, missing keys and erased temporary
buffers. Assembled UI checks verify local navigation without list HTTP requests.

API 35 emulator acceptance upgraded the existing installation without clearing
its data. The real development API published one owned September payslip, its
indexed summary and empty payment-progress record. After disabling Wi-Fi/mobile
data and force-stopping the app, Home, the annual list and the full detail reopened
locally. Selecting another year and returning retained the acquired payslip.

Advancing the emulator clock by 73 hours and restarting the app retained the same
company, payslip and financial breakdown. All three record payload hashes remained
unchanged through offline navigation and the clock adjustment. This simulates
age rather than waiting three physical days. Time, automatic time and connectivity
were restored; successful online synchronization cleared connection feedback.
Screenshots were inspected and the crash buffer had no fatal exception. The
emulator was stopped with its data retained. Multiple-record pagination and legacy
snapshot migration were covered by automated tests, not this single-slip fixture.

After notice, the validated APK was installed on the authorized Android 16 phone
at 09:15:08 on 2026-10-14 (WIB), using install-only delivery. The package update
timestamp and matching installed APK hash were verified without opening the app.

## Attendance and calendar replica

Root checks passed with 1,295 desktop/UI tests, Android dev assembly and production
Web distribution. Workforce
passed 123 domain and 63 data desktop tests, plus 123 domain and 40 data Chromium
tests. Coverage includes four-collection acquisition, staged replacement, versioned
tombstones, bounded indexed historical reads, effective schedule precedence,
overnight shifts, daylight-saving transitions and account/session replacement.
Failed hydration cannot advance a cursor, and cancelled or superseded work cannot
publish into a replacement workspace.

Shared read-admission tests cover failed persistence/cleanup, encrypted restriction
restoration, a newer denial during pending admission, and denial while capture waits
for GPS. Confirmed command reconciliation requires its exact server entry and version.
The assembled tests verify local month/week navigation, one acquisition during filter
changes, retained Home state, cross-page denial, successful retry and disposal.

Real SQLite/AES startup tests reopen both the replica and the pre-upgrade attendance
snapshot offline with three-day-old data and workspace metadata. Home becomes usable
before any successful network response; a pending renewal preserves its company and
content. Browser verification covers the IndexedDB implementation, not persistence
of memory-only browser credentials after page closure.

Action preparation now reports a request for review rather than an expired data
cache. The localization boundary accepts retained legacy failure codes with the
same action-specific copy. Read states no longer contain special handling for
cache-expiry errors, and the unused offline-access-ended screen resources were
removed. Existing action deadlines, export checks and confirmed-denial gates remain.

API 35 emulator acceptance upgraded the existing application without clearing its
data. The real development feed published three attendance days, one weekly
assignment, one roster override and the employment directory; no company holiday
was present in this fixture. After disabling Wi-Fi/mobile data and force-stopping
the app, Home, monthly history and the calendar reopened locally. Switching months
and opening the following week retained the existing records and resolved the
stored weekly rules without a network response.

Advancing only the emulator clock by 73 hours and restarting the process retained
the company, attendance history and overnight calendar intervals. Replica payload
hashes were unchanged throughout those offline reads. This is a clock-adjustment
test, not a three-day physical wait. Time, automatic time and connectivity were
restored; explicit online refresh cleared connection feedback without duplicate
records. Screenshots were inspected and the emulator crash buffer contained no
fatal exception. The emulator was stopped with its data retained.

After notice, the validated APK was installed on the authorized Android 16 phone
at 07:51:38 on 2026-10-14 (WIB), using install-only delivery. The package update
timestamp was verified, and the installed APK hash matched the validated artifact. Its application
was not opened or driven.

## Local read lifetime and cursor recovery

Root checks passed with 1,187 desktop/JVM tests and Android dev assembly. The
identity/workspace domain and Inbox domain/data suites passed 111 Chromium tests.
Coverage separates stored-data age from operation authority, restores an owned identity
after credential expiry, and retains local reads through network failure. The
assembled Compose test closes and reopens real SQLite/encrypted storage with a
three-day-old workspace and attendance snapshot, opens Home offline, and retains
the same company while renewal is pending and after it succeeds. Other cases
cover credentials expiring during renewal, cancellation, late results, company
replacement and confirmed denials through failed cleanup and pending retries.

Inbox tests retain published data while an expired cursor builds a replacement.
Real SQLite/AES storage survives database close/reopen with a staged generation,
then publishes its replacement atomically. A confirmed policy/feed/resource denial
remains an access failure if cleanup fails or hangs; assembled list/detail tests
keep that data gated until admitted synchronization succeeds.

API 35 emulator acceptance disabled Wi-Fi and mobile data, force-stopped the app
and reopened Home and the stored monthly attendance history. Advancing only the
emulator clock by 73 hours retained both the selected company and historical
attendance. Restoring time and connectivity, then refreshing, retained the same
company and cleared connection feedback. Screenshots were inspected; no HRIS crash
appeared in that emulator boot. System time, automatic time and network settings
were restored. This uses a clock adjustment, not a three-day physical wait.

This replaces the earlier one-hour local-read gate. Action reviews, exports,
confirmed authorization changes and unfetched data keep their separate rules.
See [current coverage and remaining work](local-first.md); these checks do not
establish full offline drafts or incremental sync for every feature.

After advance notice, the validated APK was installed on the authorized Android 16
phone at 23:05 on 2026-10-13 with install-only delivery. ADB reported success and
the package update timestamp was verified; the application was not opened.

## Overtime command foundation

Root checks passed with 868 desktop tests, 85 Workforce domain and 22 data Chromium
tests, and Android dev assembly. The focused domain/data run passed 129 tests.
Forty-three new cases cover competing confirmation, actual/withdrawal exclusion,
late cancellation, ownership/permission changes, expiry, response loss, immutable
replay, current policy, receipt validation and reconciliation before cleanup.

Delivery tests exercise manual/worker serialization, the deadline while waiting
for another sender, finite batch/cursor progress and retry limits. Real SQLite
tests reopen all three command types under a replacement session for the same
account, reject copied ciphertext and stale outcome revisions, preserve lost-key
evidence, and verify cancellation/account replacement during pending encryption.
HTTP tests cover exact routes, bodies, versions, idempotency keys, localizable
problems and bounded receipts. Native forms and real development API command
acceptance remain separate; no new phone installation is claimed here.

## Overtime planning preparation

Root checks passed with 825 desktop tests and Android dev assembly. Workforce
passed 56 domain and 30 data desktop tests; Chromium passed the 56 domain and
17 common data cases. Twenty new tests cover current owned-employment discovery,
read-only grants, company/session replacement, cancelled late results, finite
acquisition and review expiry, protocol bounds and localizable denial. Time-policy
cases cover overnight shifts, whole-minute limits, breaks, DST day boundaries
and actual-time limits.

The scrolled-detail regression was also corrected after CI exposed an assumption
about a row at the viewport's lower edge. It verifies visible failure feedback,
the retained scroll position and reachable activity after the viewport shrinks.
Root checks and Android assembly passed again after that correction. No additional
phone installation is claimed for the preparation boundary.

## Overtime history and request details

Root checks passed with 805 desktop tests, all 21 Workforce presentation Chromium
tests and Android dev assembly. The preceding read-boundary increment also passed
66 desktop and 53 Chromium domain/data cases. New MVI coverage exercises
late cancellation, company replacement, lease expiry, retained denial after failed
cleanup, completed storage subscriptions, history paging and offline filters.
Five layout cases and the assembled HTTP/cache/navigation flow passed. Layouts
include Indonesian dark appearance at 200% text and stable header/filter targets.

API 35 emulator acceptance used the fictional employee's real development API
requests. It verified status/month filtering, request activity and the distinct
work date and execution date following a night shift. An app-UID/API-port firewall
rule tested refresh failure, encrypted list/detail restoration after process
restart offline, and explicit online recovery. The rule was removed afterward.
The first device probe exposed refresh feedback hidden above the anchored list;
fixed feedback placement and scrolled-list regressions passed before the final
acceptance. Screenshots were inspected.

After advance notice, the validated APK was installed on the authorized Android 16
phone at 04:02 on 2026-10-13 with `--install-only`. Installation and package update
metadata were verified without opening the app. This increment delivers employee
reads; native planning, actual-time submission and withdrawal remain in progress.

## Company selection and operation feedback

Root checks passed with 747 desktop tests, five app Chromium tests and Android
dev assembly. The new assembled regression holds company acquisition pending,
checks disabled duplicate inputs, returns a connection failure and opens a second
company after retry. Layout checks cover one target per company, visible pending
feedback and fixed actions at 200% text size. Existing logout, company isolation
and retained-tab regressions also passed.

The API 35 emulator opened the picker from Account, retained it through appearance
and font changes, and returned to Home after selection against the development
API. Light and dark/150% screenshots were inspected; system preferences were
restored. No frame-time or memory benchmark was performed.

## Background process restoration

The installed application's crash log identified an SDK back-stack restoration
failure during bootstrap. The same failure was reproduced on the API 35 emulator
by backgrounding HRIS, terminating its process and reopening its retained task.
With the scoped saver, Leave restored in a new process without a new HRIS crash;
the restored page screenshot was inspected. One initial UI probe read a transient
incomplete accessibility dump; a bounded retry verified the completed page.

Root checks passed with 743 desktop tests, including four new restoration tests
and the three existing navigation ownership regressions. Five app Chromium tests
and Android dev assembly passed. The tests cover changed company, revoked session,
repeated recreation while bootstrap is unresolved, retained owners and disposal.
These observations do not measure memory, frame time or battery use.

## Published documents and saved copies

Complete root checks passed with 739 desktop/JVM tests. Chromium passed 59 document
domain tests, 24 document data tests, two localization tests and five app wiring
tests. Android dev assembly and the production Web distribution passed; Web
retains its three existing bundler warnings.

Three assembled UI regressions cover catalog filters, committed download progress,
cancel/resume offsets, export buffer ownership, denied resource access through
failed retry, and a pending save dialog across Activity stop and route disposal.
The downloaded record opens its own route instead of the upload form.

On the API 35 emulator, the real development catalog returned an uploaded PDF.
Authenticated download completed, native Save dismissal recovered, and a second
Save wrote a 607-byte PDF whose SHA-256 matched the original. The encrypted
download and correct detail route restored after process restart. The catalog
and saved-copy screens were inspected. This validation did not drive the phone
UI or measure runtime memory/frame timings.

## Authenticated export admission

Targeted document checks passed with 59 domain and 24 data desktop tests, plus
59 domain Chromium tests. Export regressions cover bounded plaintext copying,
picker dismissal, late cancellation, account replacement, access loss after
selection and during writing, final resource denial, preserved technical failures,
zero progress and finite deadlines. Catalog checks cover read-only employment
acquisition, changed-company results and lease expiry without network polling.

## Owned file output

Complete root checks passed with 719 desktop/JVM tests. The file-I/O suite passed
21 Chromium tests, localization passed two, and app wiring passed five. Android
dev assembly and production Web distribution passed; Web retains its three
existing bundler warnings. Native and browser regressions cover bounded writes,
partial writes, zero progress, picker cancellation, late results, disposal and
read/write failure classification. Apple native filesystem regressions and the
unsigned iOS simulator application build subsequently passed in GitHub Actions,
alongside Linux and Windows checks, for both HRIS Mobile and Compose Fluent Starter.

At this foundation checkpoint, employee export and native save-dialog acceptance
were separate integration work.

## Authenticated download core

Root checks passed with 711 desktop/JVM tests. Chromium passed all 42 document
domain tests and 24 data tests, and Android dev assembly passed. New coverage
checks current catalog/content admission, concurrent preparation, fresh local
identities after removal, exact authenticated ranges, interrupted block writes,
resume offsets, checksum failure, stale completion, late cancellation, changed
company access and finite transfer deadlines. The same storage cases run against
SQLite and IndexedDB. Downloaded revisions cannot enter the import/upload flow.

This checkpoint validated the shared transfer and storage contracts before
published-document browsing, OS export and real API download acceptance. It did
not include a new device installation or memory benchmark.

## Bounded binary transport

Root checks passed with 691 desktop/JVM tests, all 34 network and five transfer
Chromium tests, and Android dev assembly. Range coverage verifies retained session
and conditional headers, bounded strong validators, offset overflow protection,
exact extents, conflicting headers, compression rejection, truncated/oversized
streams, cancellation and body-free HEAD acquisition. API problem codes survive
binary rejection through the same bounded error reader used by JSON requests.
The existing transfer adapter now uses this shared reader. At that checkpoint,
HRIS download storage and its feature UI were separate integration work.

## Document upload presentation

Root checks passed with 684 desktop/JVM tests. The sync data and application
Chromium suites passed seven and five tests respectively. Android dev assembly
and the production Web build passed. Four assembled UI regressions cover form
validation, confirmed metadata and publication, pending acquisition cancellation,
retained input after detachment, and denied access through failed and successful
revalidation. English and Indonesian captures were inspected.

The full UI flow exposed a scheduler registration cycle that the earlier upload
core tests did not resolve. Deferred platform acquisition and a generated-DI
regression now cover tasks which schedule subsequent work. The expanded document
contract also required updating the Leave evidence fixture for clean CI compilation.
The shared scheduler correction was ported to the starter with its own checks.

The API 35 emulator uploaded a 607-byte PDF through the real development API and
reached server `READY` after content inspection. The encrypted local record and
completion status restored after process restart. Phone ADB connection timed out,
so this feature update has not yet been installed on the physical device.

## Resumable document upload core

Complete root checks passed with 679 desktop/JVM tests. The document domain and
data suites also passed 46 Chromium tests. Coverage includes durable confirmation,
duplicate admission, response loss, cancelled chunk transport, changed account
access, late receipts, bounded automatic attempts, validation recovery and
preserved cancellation intent. SQLite and IndexedDB checks verify encrypted
checkpoints, immutable input, competing revisions and bounded authenticated chunks.

The isolated browser suite loads the same IANA timezone data as the application;
without it, valid employment-directory responses failed timezone validation. The
Wasm lockfile was regenerated with Gradle and reviewed. This checkpoint does not
include a new APK or device upload acceptance; those accompany the feature UI.

## Retained Home and Employees tabs

Complete root checks passed with 631 desktop/JVM tests, four build-endpoint tests
and seven Android runner tests. Android dev assembly passed. Four new assembled
UI cases cover repeated tab changes without new HTTP requests, retained search
and scroll, manual refresh, cancellation with loaded rows, hidden access changes,
expired-cache recovery and observation cleanup. The initial probes reproduced
Home returning to loading and the missing hidden read observer before the fix.

The full flow suite initially switched its transport offline before a returning
Leave overview had finished refreshing. That setup now waits for the overview
before exercising offline detail recovery; the complete suite passed afterward.

The API 35 emulator completed three Home/Account/Employees round trips with Wi-Fi
and mobile data disabled and the API confirmed unreachable. Attendance values
and the employee query remained visible. Both connections were restored and
the Home/directory captures were inspected. The same APK was installed on the
authorized phone using `--install-only`; phone UI was not driven. Runtime heap
use and frame timings were not measured.

## Private VPN development connection

Root checks passed with 627 desktop/JVM tests, four build-endpoint tests and seven
Android runner tests. Android dev assembly passed. Endpoint coverage rejects public
and link-local HTTP, credentials, queries/fragments and HTTP in staging/production.
Runner coverage verifies selected-device forwarding, failure before installation,
invalid ports, bounded ADB commands and installation without launching the app.

The API 35 emulator signed in, selected the fixture company and restored Home after
process restart with an empty ADB reverse table. The Home capture was inspected.
The same APK was installed on the authorized phone with `--install-only`, and its
obsolete API reverse mapping was removed. No phone UI was driven. The owner
confirmed successful sign-in to Home using the new connection. The earlier
loopback connection was also restored and checked before this update.

The development API now uses the private WireGuard interface. Changing from the
old loopback origin requires sign-in again; retained commands/files keep their
original origin binding. This configuration does not migrate their ownership.

## Durable file blocks and checkpoints

Complete root checks passed with 627 desktop/JVM tests. All 23 database Chromium
tests passed, including 11 new cases shared with SQLite. Coverage verifies atomic
block/progress writes, immutable completed content, checkpoint contention, stale
deletion, account replacement, bounded reservations/pages, cancellation, independent
connections, restart and migration with retained commands. Android dev assembly and
the production Web build passed. Web retains three existing bundler warnings.

The validated APK, including the Leave evidence selector, was installed on the
authorized Android phone with `--install-only`. No application launch, screen
interaction or physical-device acceptance is claimed. The preceding selector
commit `c2a9d41` passed Linux, Windows and Apple CI. File acquisition and the HRIS
upload protocol remain separate work; this raw storage boundary accepts opaque
envelopes and does not itself encrypt content or deliver it.

## Leave document selection

Complete root checks passed with 616 desktop tests. All 52 Leave presentation
tests and both localization tests passed in Chromium. Android dev assembly and
the production Web build passed. New coverage verifies bounded selection,
original revision IDs during interrupted delivery, required-attachment removal,
closed-dialog callbacks, pending cancellation, read expiry and access gates
through failed refreshes. The assembled HTTP/SQLite application test closes a
picker with a request pending, verifies cancellation, then selects required
evidence and recovers one unchanged submission after response loss.

Controlled layouts verify fixed close controls, long document names, pagination
and attachment removal at 200% text. The screenshot was inspected. One new test
initially searched for a lazy-list item before scrolling it into composition;
the corrected test traverses the list and keeps the visibility/enabled assertions.
Web retains its three existing bundler warnings. Runtime performance was not
measured by these checks.

API 35 emulator acceptance selected an existing processed personal PDF and
submitted a one-day request under a policy requiring evidence. The development
API confirmed the original document/revision, size and digest, with one day
reserved and one remaining. Withdrawal restored two available days and zero
reserved while preserving the original attachment and withdrawal history. The
isolated allowance was then reversed and its policy retired through the API.
Light/dark appearance and 200% text were inspected; the emulator returned to Home
with normal text and light appearance. Later installation is recorded above;
there is no physical-phone interaction claim for this acceptance.

The preceding catalog commit `83cbd4d` passed Linux, Windows and Apple CI. Server
commit `8ed91d3b` passed its full backend, dashboard and live integration jobs.

## Owned document catalog

Complete root checks passed with 604 desktop tests. All 12 new document domain
and data tests also passed in Chromium. Coverage includes company/account changes
during pending work, cancellation, finite acquisition, expired policy recovery,
maintenance admission, scoped metadata, bounded pages and malformed file facts.
The Wasm lockfile adds only the new workspace packages. The local browser check
uses the installed Playwright Chromium executable; no Android build or device
interaction is claimed for this boundary.

## Leave creation interface

Complete root checks passed with 592 desktop tests, plus all 42 Leave presentation
tests in Chromium and two localization Chromium tests. Android dev and production
Web builds passed. New coverage verifies typed form events, superseded previews,
date/portion bounds, expiry, stopped owners, access gates through failed retries,
completed observers, retained response-loss recovery and canonical reconciliation.
An assembled HTTP/SQLite UI test commits a request, loses its response, reopens
the form offline and verifies an unchanged replay with one server-side creation.
Controlled layouts cover multiline reasons, fixed Back/close controls, disabled
dates, expired confirmation and active sending versus an uncertain outcome.

CI run `38188709062` exposed a layout-test assumption: finding a lazy-list section
heading does not ensure the following date has been composed. The test now scrolls
to the excluded date itself and asserts that it is displayed. Formatting and the
complete root checks passed after the correction. This changes test navigation
only; no additional device installation or platform build is claimed.

API 35 emulator acceptance used the development API. A three-date selection
excluded Saturday and charged two half-days as one day. A separate half-day
request was confirmed with its API connection blocked, restored after process
restart while still blocked, and delivered explicitly after connection recovery.
Its original reason remained intact and canonical pending status opened afterward.
The available balance changed from 12 to 11.5 days with 0.5 reserved. Withdrawal
returned it to 12 available and zero reserved; both test requests retain their
withdrawal history.
The native form and calendar were inspected in Indonesian, including dark mode
with 200% text; the close action and all dates remained reachable. The emulator
was returned to its normal light appearance and text scale afterward.
The initial forwarding-only interruption did not close existing HTTP connections;
the successful offline check used an emulator-only, app/port-scoped firewall rule,
removed afterward. Web retains three existing bundler warnings. No physical-phone
interaction or runtime performance benchmark is claimed by these checks.

## Inline loading

The optional compact loading layout passed root checks, Android dev assembly and
the production Web build. Its shared UI regression keeps both the label and an
adjacent action visible at 200% text in a bounded viewport. The default layout is
unchanged. Runtime performance was not measured.

## Controlled date picker

Root checks passed with 574 desktop tests, Android dev assembled and Web
compilation passed. The new shared UI regression covers caller-owned selection,
disabled dates/month navigation, full-date accessible labels and 200% text at
320 dp. Its screenshot was inspected after shortening the weekday labels.
No phone interaction or runtime performance measurement was performed.

## Durable leave submission

Complete root checks, 86 Leave domain/data Chromium tests, Android dev assembly
and the production Web build passed for the submission boundary. Twenty-nine new
tests cover preview-bound admission, immutable input during pending work, duplicate
confirmation, replacement scopes, cancellation, finite retries, lost responses,
receipt validation and canonical reconciliation. Real SQLite tests verify encrypted
process/session restoration, cross-partition key ownership, tampered envelopes,
outcome races and cancellation before publication. Web retains three existing
bundler warnings. The creation page is not connected by this increment.

The development API preview still excluded the selected Saturday, proposed a
half-day deduction and left the existing balance unchanged. The temporary probe
session was closed afterward. No phone interaction or performance measurement was
performed for this boundary. The preceding `b27a9c6` mobile CI passed on Linux,
Windows and Apple. Server revision confirmation is implemented separately in
[HRIS Platform](https://github.com/fajarxfce/hris-platform/commit/390e567).

## Leave preparation boundary

Root checks pass with 544 desktop tests; all 64 Leave domain/data tests pass in
Chromium. Android dev and production Web builds pass. Thirteen new regressions
cover catalog bounds and cursors, inactive choices, exact preview date partitions,
separate annual balances, malformed responses, permission and company changes,
replacement sessions, cancellation, finite deadlines and mutation of input while
acquisition is pending. The creation interface is not connected by this increment.

The preceding `2472964` mobile CI and `f63b94f` starter CI completed successfully
on Linux, Windows and Apple. The server preview is independently implemented and
verified in [HRIS Platform](https://github.com/fajarxfce/hris-platform/commit/b7182e7).
There is no new phone interaction or performance measurement for this boundary.

## Leave action interface

Root checks pass with 531 desktop tests; all 29 Leave presentation tests and both
localization tests pass in Chromium. Android dev and production Web builds pass.
The new MVI regressions cover duplicate confirmation, current-version admission,
cancelled and late work, permission recovery, process-independent command recovery,
and completed-source resubscription. An assembled UI test loses the response after
the server fixture commits, restores the original command, recovers its receipt
and withdraws the resulting pending cancellation. It asserts the original request
body and operation ID rather than treating a new submission as a retry.

Shared layout tests cover multiline editing at 200% text and the dialog's fixed
close action. The generic field and regression also pass in Compose Fluent Starter.
The earlier `b95bb03` CI run completed successfully on Linux, Windows and Apple,
including the Home publication correction and durable-command foundation.

Against the development API, the API 35 emulator withdrew a pending half-day
request and recovered its reservation: available balance changed from 12 to 12.5
days and reserved balance from 0.5 to zero. For a separate approved request,
cancellation was retained offline, restored after process termination, and sent
after reconnecting. Withdrawing that pending cancellation returned the request to
approved; consumed balance stayed at 0.5 days throughout both cancellation actions.
Light/dark appearance, 200% text, multiline editing and dialog dismissal were
inspected. Normal text size, light appearance and API forwarding were restored.
These transactions use fictional development data; new mobile request creation
and evidence upload remain separate work. No performance measurement is claimed.

After advance notice, the validated dev APK was installed on the connected
Android 16 phone at 05:26 on 2026-10-12 with `--install-only`. Package update time
and API forwarding were verified without launching or interacting with its UI.

## Durable leave command foundation

Root architecture, formatting, static analysis and all 519 desktop tests pass.
The 51 Leave domain/data tests also pass in Chromium, and Android dev assembles.
Twenty-seven new command tests cover admission, recovery, deadlines, concurrency,
encrypted persistence and transport validation. The Home publication regression
also passes in the complete suite. UI controls and device acceptance for these
transactions remain the next increment; no release was created.

## Home layout during cache publication

A controlled cache notification reproduced a 113 px upward menu shift after
attendance acquisition completed but before its snapshot reached the UI. Home
now retains its loading layout until the content arrives. The regression, both
calendar scenarios that failed in hosted CI, and Home lifecycle/location recovery
pass as four focused assembled UI tests. The test still activates the menu through
its pointer action. No frame-time or heap measurement is claimed.

At `eee789e`, Windows and Apple CI passed; Linux verification failed in the two
initial calendar navigation scenarios. That failed run is not counted as a pass.

## Leave request presentation

The root check passes with 491 desktop tests, and Leave presentation passes
20 Chromium tests. Android dev and production Web build successfully. New tests
cover route-owned subscriptions, pending-read cancellation, late denial, history
cursors and restoration, current metadata with earlier history, expired reads,
failed cleanup and retries, upstream storage-flow completion, and company
replacement. Assembled UI tests open real feature routes through generated DI,
exercise offline recovery and unavailable-request gates, and verify Indonesian
at 200% text with fixed navigation. Manual refresh probes wait for the visible
action to become enabled; transport completion alone did not establish UI readiness.

The API 35 emulator opened one fictional half-day request from the development
API. Submitted date/portion, duration, reason, current status and activity matched.
Force-stop/restart with the emulator's API forwarding removed restored the saved
request; reconnecting and refreshing removed the connection error. Light/dark
and 200% text were inspected, and normal text size, appearance and forwarding
were restored. This validates request reading, not mobile submission. No frame-time
or memory benchmark is claimed.

After advance notice, this APK was installed on the connected Android 16 phone
with `--install-only`; the package update and API forwarding were verified.
The phone application was not launched or driven for this delivery.

## Leave request read boundary

The root check passes with 478 desktop tests. Leave domain/data also passed
31 Chromium tests, and Android dev assembles. Seventeen new tests cover owner-only
request transport, current metadata with older history, malformed responses,
cancellation, late results, lease expiry, generation conflicts, independent cache
windows, encrypted restoration and corrupt/missing records. A 366-day fixture
with ten multibyte history entries verifies bounded chunking beyond a single
96,000-byte record. The existing nine presentation browser tests remain unchanged.

The development API accepted one fictional half-day request and returned it through
the owner-only detail route. Request-detail UI, transaction submission and physical
device acceptance are not claimed for this foundation change.

## Protected reads and leave balances

The 2026-10-12 update passed root checks with 461 desktop/JVM/UI tests, all nine
Leave presentation Chromium tests, and Android dev plus production Web builds.
The preceding Leave data/domain Chromium runs passed their 18 tests. Regression
tests reproduce failed cache cleanup, denied reads followed by failed retries,
expiry during pending acquisition, fresh publication, and stopped/disposed
observers. An access or MFA failure cannot be replaced by a later network/storage
failure to reveal the old snapshot.

The API 35 emulator read the real development calendar, navigated to the previous
week and back to the current week, returned to Home, and opened the fictional
12.5-day leave balance. The earlier Leave acceptance also covered offline process
restoration, online recovery, English/Indonesian, both themes and 200% text.
Request submission and physical iOS acceptance remain separate work.

The validated dev APK was installed on the authorized Android phone at 03:38 on
2026-10-12 with `--install-only`. Installation and package metadata were verified;
the phone UI was not launched or driven.

## Monthly attendance interface

The 2026-10-12 update passed root checks with 425 desktop/JVM/UI tests, the
localization Chromium suite, and Android dev and production Web builds. The
workforce domain Chromium suite also passed for the monthly acquisition/summary
implementation. Screen tests cover leap-year filters, future-month restrictions,
metrics, read-only day details, cancelled month requests, company replacement,
offline refresh, and expiry/MFA during pending acquisition. Cached observation
stops on a denied read and resumes after successful refresh. Tests cover both
arrival orders of cache and network results when restoring a saved month offline.

The API 35 emulator exercised the real API's pending attendance, monthly navigation,
day details, English/Indonesian, light/dark, and the month picker at 200% text.
The final APK restored September after an offline process restart and kept that
month on online refresh. Home retained an explicit capture without location after
restart; retry sent it and the next snapshot removed it from the device queue.
It remained pending verification with zero verified time. GPS did not produce a
valid location during this queue test, so this is not a physical-location check.

The final dev APK was installed on the authorized Android 16 phone at 02:13 on
2026-10-12 using `--install-only`. Installation and package metadata were verified;
its UI was not opened or driven. No release was created.

The reusable month picker and date formatting are also ported to the starter;
HRIS metrics, access policy, cache recovery and capture workflows remain here.

## Monthly attendance foundation

The acquisition and storage update passed root checks with 420 desktop/JVM/UI
tests, workforce domain Chromium tests and the Android dev build. Regression
coverage includes complete/leap-year months, invalid/future filters, separate
recent/history caches, dense monthly ciphertext, v1 cache recovery, cancellation
during encryption, key-buffer cleanup, company changes and stale results. Domain
tests distinguish confirmed absences, unrecorded/off days, queued captures,
pending review and ongoing shifts. This validates the data foundation; the new
Attendance screen is delivered separately.

## Inbox totals and navigation badges

The 2026-10-12 update passed the root checks with 410 desktop/JVM/UI tests,
the database and Inbox domain/data Chromium tests, and the Android dev build.
The full-count and adaptive-navigation UI tests were repeated to capture the
badge: 36 unread messages remain 36 with a 30-item page, then become 35 after an
explicit read. Tests also cover cancelled reads, company replacement, zero
counts, large text and full accessible count descriptions.

The updated APK was installed on the dedicated API 35 emulator. The real API
Inbox, Employees and Back-to-Home flow were checked; the existing emulator inbox
messages were already read. Positive badge counts were verified with isolated
API/UI fixtures. The phone was offline in ADB, so this update was not installed
there. No release was created.

## Earlier company and inbox integration

The company-policy, workspace-admission and inbox implementation passed these Linux checks:

```sh
xvfb-run -a ./gradlew :check \
  :core:workspace:domain:wasmJsBrowserTest \
  :core:workspace:data:wasmJsBrowserTest \
  :features:inbox:domain:wasmJsBrowserTest \
  :apps:android:assembleDevDebug
```

The root check includes architecture validation for 52 modules, Kotlin formatting,
static analysis and JVM tests. The assembled application passed 24 Compose UI
tests, three application-MVI tests and one link-parser test. Workspace domain
tests passed on JVM and in Chromium (21 each), with six SQLite/encryption tests on
JVM and three mapper tests on each JVM/browser target.
Inbox domain tests passed on JVM and in Chromium (37 each); inbox data
passed 13 JVM tests. Its two browser transport tests passed in the preceding
inbox-command run. Tests cover scope replacement, expiry,
pending cancellation, late outcomes, finite replay, conflicts, encrypted command
retention, response loss followed by login, and route disposal. Browser transport
tests verify the serialized command and original idempotency key.

Workspace-specific regressions cover lease renewal without a refresh loop or inbox
reset, MFA/session deadlines, late outcomes, foreground cancellation, concurrent
snapshot replacement and failed-revocation storage recovery. The shared atomic
snapshot primitive passed JVM and actual Chromium storage checks in both HRIS
Mobile and Compose Fluent Starter; both repository CI runs also passed.

The earlier protected-credential checks passed eight JVM and thirteen browser
tests, including concurrent record creation, reopening, tampering and cancellation.
The Android-compatible file-lock correction passed the root check and dev build
in both this repository and Compose Fluent Starter.

Gradle dependency verification is enabled using the committed SHA-256 metadata.
See [quality gates](quality.md) for updates and their trust boundary.

Screenshots from UI tests are under `apps/shared/build/reports/screenshots`.
The CI workflow additionally builds an unsigned iOS simulator application and
Windows classes. These are compilation checks, not physical Apple/Windows vault
tests or signing/distribution validation.

Company-policy regressions cover revision zero, platform build admission,
microsecond freshness deadlines, scheduled maintenance after a stale snapshot,
flag changes without cache or command loss, late denied access, scope replacement,
and a pending bootstrap failure followed by successful retry. Virtual-time tests
verify the finite observation deadlines; no clock change was made on the device.

## Android device observations

The dev application was installed on a Samsung Galaxy A72 running Android 16,
connected through WireGuard/ADB to the actual local HRIS API. A fictional employee
and published dev announcement were used. Checks covered:

- Onboarding, password sign-in, two company selections and current employee profile.
- Company-isolated inbox contents and a selectable plain-text message.
- Indonesian language persistence, dark appearance, background/foreground return
  and credential restoration after process termination.
- An admitted message remaining readable while a device-only proxy closed its
  network connections, followed by successful refresh after reconnection.
- Explicit read, acknowledgement-prompt cancellation, an offline acknowledgement
  retained after force-stop/restart, and successful retry/reconciliation with the
  real backend. The completed message showed `Dikonfirmasi` without pending actions.
- Force-stop/restart with the API connection still disabled, restoring the admitted
  company and readable inbox without another company selection. Reconnection and
  foreground revalidation retained the open detail.
- Replacing that company, then restarting offline into its empty inbox without
  exposing the first company's messages.
- Minimum-build and maintenance gates from actual company policy revisions,
  switching to an unaffected company, and explicit retry after maintenance removal.
- Disabling People and Communications while Inbox was open, hiding its content
  and the affected home actions. Another company retained both actions. Re-enabling
  the modules restored the message and its confirmed acknowledgement.
- An invalid authenticator code producing localized feedback without ending the
  verification flow, followed by successful verification and company selection.
- Authenticator enrollment with a separate fictional account, acknowledgement of
  ten recovery codes, company restoration after force-stop/restart, and a subsequent
  sign-in using one recovery code. Proofs were generated from fixture setup secrets;
  an external authenticator application was not part of this check.
- Cancelling another device's session-revocation confirmation, then confirming it.
  The disposable session remained valid after cancellation and its token received
  `401 native_session_invalid` after confirmation. The current device remained usable.

The company-policy APK was used for the availability checks, and the workspace APK
for offline startup and foreground revalidation. The preceding command/inbox APKs
were used for the earlier observations. The proxy was stopped, the normal dev API
connection restored, and the demo company's original policy values restored after
testing. The disposable session was revoked and the temporary MFA account was
signed out and its company grant disabled. The original employee account was restored afterward.
No frame-time, heap or battery measurements were taken. External SSO, push delivery,
and iOS device behavior still need their own physical checks.

## Android emulator observations

The existing company-policy dev APK was also installed on a dedicated headless
Android 15/API 35 x86_64 emulator using KVM and software graphics on Linux. Against
the local HRIS API it passed onboarding, fictional employee sign-in, company
selection, inbox reading, and acknowledged-message display. Removing only the
emulator's API tunnel and restarting the process restored the admitted company
and its readable saved message. The normal API tunnel was restored afterward.
These checks did not interact with the physical phone.

Both HRIS Mobile and Compose Fluent Starter passed their root checks and four
Android-runner tests covering target selection, an ambiguous/disconnected device,
missing artifacts, and updating without an application launch. No application
runtime code changed for this tooling update; the existing APK was used for the
emulator observations. See [development](development.md#headless-android-emulator)
for reproduction. No emulator performance or memory benchmark is claimed.

## Integration boundaries

Runtime composition uses the HRIS API, not the starter demo transport. Compose
tests use wire-contract fixtures. The platform's separate PostgreSQL/API/worker
integration covers native authentication, recipient isolation, command replay and
withdrawal. Those checks complement device tests; none establishes production
capacity. Web bearer credentials remain ephemeral, so page-close workspace
restoration is unavailable there. Inherited starter notification/transfer
modules are not evidence that those HRIS workflows have been integrated.

## Employee layout refinement

Home now separates company selection from employee actions; account preferences
and session controls use grouped rows. Calendar and inbox share compact headers,
and calendar time ranges have clearer emphasis than saved/source metadata.

Root architecture, formatting and distribution checks passed with all 351 desktop
tests, including retained offline content, navigation ownership and cancellation
coverage. A shared 360 dp layout regression checks 200% text, heading semantics,
icon targets, one action per tile and keyboard activation. Android dev and Web
distribution builds passed; Web retains its existing bundler warnings.

The API 35 emulator opened Home, Account, weekly calendars, the inbox and message
details against the real development API. Week navigation returned to the current
week. English/Indonesian, light/dark and 150% text captures were inspected; the
workspace survived appearance and text-size changes. An initial probe read the
previous week before its response arrived; waiting for the expected date verified
the completed navigation.

After advance notice, the same APK was installed on the connected Android 16 phone
with `--install-only`. Installation, package update time and the API forwarding
rule were checked without launching or driving its UI. These observations do not
establish frame-time, heap or battery performance. The reusable components and
regression were also validated in Compose Fluent Starter with 198 tests and
Android/Web builds.

## Leave layout and action placement

The Leave overview and request detail keep their available actions below the
scrolling content. Creation keeps submission below its calculation review.
Root checks passed with 749 desktop tests, five application Chromium tests and
Android dev assembly. Regressions cover fixed actions at large text sizes, disabled
actions during refresh/recovery, denied reads, and the original durable submission
after response loss.

The API 35 emulator opened balances, creation and existing request details against
the development API. Light appearance, dark appearance at 150% text, fixed request
actions and return navigation were inspected. The shared layout tests also cover
Indonesian at 200% text. No frame-time or memory measurements were taken.

## Document navigation and transfer actions

Root checks passed with 755 desktop tests, five application Chromium tests and
Android dev assembly. Coverage includes direct catalog navigation, interrupted
metadata acquisition when opening local files, native-picker stop/resume, late
results after route disposal, upload confirmation, retained denied access,
resumable ranges, authorized export and obsolete navigation-snapshot rejection.
Shared layout tests cover Indonesian at 200% text and fixed transfer actions.

The API 35 emulator opened the real published catalog, changed categories,
downloaded a document, opened and cancelled the native Save dialog, then imported
a PDF through the native picker directly into its upload form. Light appearance
and dark appearance at 150% text were inspected. The shared tab component also
passed the starter's root checks, 265 desktop tests, two application Chromium
tests and Android dev assembly. No performance or memory benchmark is claimed.

## Dedicated employee search

Root checks passed with 759 desktop tests, five application Chromium tests and
Android dev assembly. Search has its own navigation-owned ViewModel, query and
filter. Regressions cover pending cancellation on Back, discarded late results,
owner disposal, company replacement, retained browsing filters/pages/scroll,
explicit retry, and fixed search/Back controls with Indonesian text at 200%.

The API 35 emulator verified the browsing entry, automatic keyboard focus, real API
results, an empty result, clearing, return to the retained directory, and Back to
Home. Light appearance and dark appearance with 150% text were inspected; emulator
preferences were restored afterward. The shared controls also passed the starter's
267 desktop tests, two application Chromium tests and Android dev assembly.
These checks do not establish frame-time, memory or battery performance.

## Overtime planning and delivery

Root checks passed with 887 desktop tests, 89 Workforce domain and 31 presentation
Chromium tests, Android dev assembly and application Web compilation. Nineteen new
cases cover company-local
time conversion, DST gaps/folds, duplicate confirmation, pending cancellation and
late results, owner disposal, review expiry, denial through failed recovery,
completed storage observation, explicit discard and immutable delivery. The
assembled application test recovers a lost successful response through feature
routes, the HTTP adapter and encrypted command storage without a second commit.

Shared layout checks verify fixed Back/submit controls, disabled actions during
refresh, date bounds and Indonesian text at 200%. Planning, the time dialog,
unconfirmed delivery and the real request screenshots were inspected.

The API 35 emulator selected a reviewed overnight schedule and entered company-local
19:00–21:00 time with a 15-minute break. It retained the command while only its API
transport was blocked, then restored the original date, interval and reason after
process restart. Explicit replay opened the admitted request. A separate API read
verified exactly one matching plan at version zero with 105 worked minutes. The
first device probe was corrected to wait for keyboard focus and tolerate the
transient UI dump during process startup; no app behavior changed for those probe
corrections. All owned transport blocks were removed and probe sessions closed.

After notice, the validated APK was installed on the authorized Android 16 phone
with `--install-only`. This device update did not launch or drive its UI. These
checks do not establish performance or physical-device acceptance of the new form.

## Overtime actual time and withdrawal

Root checks passed with 903 desktop tests, five shared-presentation and 40 Workforce
presentation Chromium tests, Android dev assembly and application Web compilation.
Sixteen new cases cover action preparation, immutable replay after a lost successful
response, conflicting action entry, request-version changes, denial through failed
recovery, storage-flow completion, cancellation, late results and owner disposal.
Repeated Back during pending delivery produces one navigation effect.

The shared enum adapter preserves named saved-state values and graph identity across
reconstruction. Unknown values cannot select a fallback action. The application
regression traverses the real feature graph, HTTP adapter and encrypted store,
recovering actual time before a separate confirmed withdrawal. Layout tests cover
fixed controls, disabled duplicate inputs and Indonesian dark appearance at 200%
text. Actual-time, withdrawal and resulting-request screenshots were inspected.

API 35 emulator acceptance used the fictional employee and an effective development
overtime approval template. Company-local 19:10–20:50 with a 15-minute break remained
queued while the application's API transport was blocked. After background process
death, both the typed action route and original input restored offline. Explicit
replay created version one with 85 worked minutes and one actual-submission history
entry. A subsequent HTTP read timed out; the confirmed receipt stayed visible and
explicit Open request recovered without another submission.

Opening withdrawal and pressing Back preserved version one. Explicit confirmation
then produced version two, one withdrawal entry and a cancelled approval. The final
request retained the original planned and actual evidence. Separate API reads
verified these outcomes. All owned transport blocks were removed and probe sessions
closed.

After notice, the validated APK was installed on the authorized Android 16 phone
with `--install-only`. The app was not opened or driven on that phone. No physical
device acceptance or performance measurement is claimed for these forms.

## Read-cache renewal

On 13 October 2026, root checks and Android dev assembly passed with 1,163
desktop tests. Eighteen new cases cover automatic renewal of visible expired
leave, overtime, expense and payslip reads; a pending acquisition keeps old data
hidden, failed acquisition does not loop, and cancellation rejects late results.
An actual one-hour timer verifies that renewing workspace admission leaves the
old snapshot's deadline intact and its later expiry acquires new data without
losing the selected year. MFA denial remains gated through network/storage
failures even when old encrypted content survives cleanup.

The 139 assembled application tests include attendance and calendar renewal,
expiry during pending acquisition, hidden Home expiry without a network request,
and account/company isolation. An Android API 35 emulator opened Home and
Employees repeatedly, loaded attendance history from the development API, and
returned from background with the same application process. Home screenshots
were inspected. Expiry timing is exercised by the automated tests.

After notice, the APK was installed on the authorized Android 16 phone with
`--install-only`. No interaction with that phone's application was performed.


## Account-owned Inbox replication

On 14 October 2026, root checks passed with 1,195 JVM/desktop tests, including
assembled application flows. Chromium passed 24 database, 49 Inbox domain and
three Inbox transport cases. Android dev assembly passed. The new shared storage
contract separates the active session guard from stable API/account ownership;
Room and IndexedDB migrations preserve published/staging rows and tombstones.
Tests cover logout/new-login retention, account/API/company isolation, late writes,
atomic checkpoint publication, missing keys for inactive accounts, copied ciphertext,
old-format reads, and permission denial during failed cleanup.

API 35 emulator acceptance upgraded the existing schema 8 database to schema 9
without clearing application data. Inspection confirmed the preceding Inbox
ciphertext was retained byte-for-byte. The new account projection and its detail
opened after an offline cold start. Logout removed temporary rows while retaining
the encrypted account records. After sign-in, before company selection or another
feed acquisition, the stored account/ciphertext matched and the local session ID
was different. Selecting the admitted company and restarting offline reopened the
saved Inbox. Screenshots were inspected; no AndroidRuntime or SQLite error appeared
in the inspected device log. Network access was restored after the probes.

After notice, the 29,229,579-byte APK was installed on the authorized Android 16
phone at 00:39:19 WIB using `--skip-build --install-only`. Its SHA-256 is
`ce48523c1b76f74631f4d59d6e30ee079fc2eac15e1e2108875844ee88461f76`.
The phone application was not opened or driven. Other feature collections,
persistent offline drafts, and a shared sync-status presentation remain pending;
these checks do not claim complete offline coverage or measure memory/frame time.

## Indexed replica reads

The raw replica store supports bounded equality-tag queries. Multiple pages share
one published generation/revision and active account check; record, index and
cursor changes commit together. SQLite and IndexedDB tests cover filtering, ordered
pagination, status-index replacement, stale updates, deletion, rollback, staged
publication, observer disposal and replacement sessions. Both migration suites
retain untagged account ciphertext, pending generations and tombstones before
adding indexes.

Root checks passed with 1,199 JVM/desktop tests. The database (25) and secure-storage
(16) Chromium suites passed, including independent HKDF/HMAC vectors and cancellation.
Android dev assembly passed. This validates the storage foundation; it does not
claim that Leave is already connected to its change feed or that UI/device
acceptance has been performed for this increment.

## Account-owned Leave replication

On 14 October 2026, root checks passed with 1,225 JVM/desktop tests, including
the assembled leave workflows and application resource ownership. The Leave
domain/data/presentation Chromium suites passed 161 tests. Android dev assembly
passed. Tests cover staged bootstrap, incremental hydration, local year/status
queries and pagination, expired cursors, same-account reauthentication, late
results after company changes, denied reads through failed cleanup, cancellation
during encryption, database reopening, authenticated index metadata and atomic
record/checkpoint publication. UI and worker entry points resolve the same sync
execution owner; both legacy and replica credential stores close with the app.

API 35 emulator acceptance upgraded the existing app with connectivity disabled.
The preceding leave overview rendered with unchanged ciphertext before any new
replica existed. Reconnecting acquired five requests, two balances and one
directory document in the published generation, with 12 filter-index entries and
no pending generation. After force-stop and offline restart, annual balances and
request history opened; year and status filters queried the saved records without
changing their ciphertext. The final APK repeated the offline restart check.
Screenshots were inspected and the inspected crash buffer recorded no fatal
exception. Emulator connectivity was restored afterwards.

After notice, the 30,105,761-byte dev APK was installed on the authorized
Android 16 phone at 02:28:11 WIB using `--skip-build --install-only`. ADB reported
success and package metadata confirmed the update. Its SHA-256 is
`37edc965834c3c92f5851ba5e228e68ea910233077159a2e98b54cf256d2a3e2`.
The phone application was not opened or driven.

Full request details/activity still use the previous last-view snapshot. These
checks do not claim complete historical-detail replication, offline creation of
every draft, or measured memory/frame-time improvements.

## Leave request and activity replication

On 14 October 2026, root checks passed with 1,242 JVM/desktop tests, including
assembled application flows and resource ownership. The Leave domain, data and
presentation Chromium suites passed 178 tests. Android dev assembly passed.
Tests exercise independent request retention, initial acquisition before the feed,
staged format migration, authoritative absence, bounded dense records, historical
cursor progress, local page reuse and unchanged action-review timestamps. SQLite
and IndexedDB cover atomic publication, competing versions, stale children,
interrupted encryption, cancellation and plaintext cleanup. MVI regressions open
stored activity pages without HTTP and preserve known denials through recovery.

API 35 emulator acceptance upgraded the existing installation with connectivity
disabled. The preceding overview and request-summary ciphertext remained unchanged.
Reconnection acquired all five full requests, producing 23 published documents,
including five detail headers and five activity pages, with no pending generation.
After force-stop and offline restart, two previously unopened requests displayed
their reasons, dates, status and activity independently; one also displayed its
attachment metadata. Screenshots were inspected. The previous request snapshot
partition was empty on this device; compatibility with that format is covered by
the encrypted migration tests. Older-page backfill is covered by automated tests,
not by these short device histories.

A second offline restart reopened the first request after visiting the other one.
The inspected crash buffer contained no fatal exception. Emulator connectivity
was restored and its process stopped with application data retained.

After notice, the 30,121,720-byte dev APK was installed on the authorized Android 16
phone at 03:57:35 WIB using `--skip-build --install-only`. ADB reported success and
package metadata confirmed the update. Its SHA-256 is
`14df12ac36b1b908fa3d296a677ec22d40557585924956432a5cf8f75a7f06b5`.
The phone application was not opened or driven. Other business replicas, unfinished
offline drafts and shared sync-status presentation remain pending.

## Account-owned Overtime replication

On 14 October 2026, root checks passed with 1,267 desktop tests, including assembled
application flows and resource ownership. The Workforce domain, data and
presentation Chromium suites passed 179 tests. Android dev assembly passed.
Tests cover change-feed hydration, versioned deletion, interrupted staging,
same-account reauthentication, local date/status pagination, retained request and
activity records, failed-denial cleanup, cancellation and mutex-wait deadlines.
The SQLite and IndexedDB suites share encrypted-storage contracts for independent
requests, legacy migration, authenticated indexes, missing keys and stale writers.

API 35 acceptance upgraded the existing installation with connectivity disabled.
The preceding overview and selected request remained readable with unchanged
ciphertext before any new replica existed. Reconnection acquired two full requests,
two summaries and one directory document through the real API, with no pending
generation. One request had never been opened on the device before this sync.
After force-stop and offline restart, both requests displayed their own planned or
actual time, reason and frozen schedule. The first remained available after viewing
the second and another process restart. Month and status filters queried local
records while the device remained offline; all replica and legacy ciphertext
hashes were unchanged by those reads.

Screenshots were inspected, and the inspected crash buffer contained no fatal
exception. Emulator connectivity was restored after acceptance. These short request
histories required no older-page backfill; that behavior is covered by automated
tests, not claimed as device pagination acceptance.

After notice, the 29,304,638-byte dev APK was installed on the authorized Android 16
phone with `--skip-build --install-only`. ADB reported success and package metadata
confirmed the update at 05:06:32 WIB. Its SHA-256 is
`ecd5a52eef7d3d1592d05d41d852c16e2e92154f23fa9510ab63241cfaaa6ad7`.
The phone application was not opened or driven. The emulator process was stopped
with application data retained. Attendance, Expenses and Payslips replicas,
unfinished offline drafts and shared sync-status presentation remain pending.

## Roster reset synchronization

On 16 October 2026, root checks passed with 1,535 desktop tests, including the
assembled application. The Workforce domain Chromium suite passed 128 tests;
Android dev assembly and the production Web distribution passed. Five new domain
regressions cover a withdrawn upsert before a later deletion, reset during
bootstrap, older references after a newer acquisition, unrelated failures and
pending cancellation. One attendance navigation test timed out during the initial
combined build; its isolated rerun and the complete standalone root check passed.

An API 35 emulator used an isolated API/PostgreSQL/worker and disposable accounts.
The public API created a weekly day shift, a company holiday and an explicit
roster day off. Reset changed the local roster from version zero to a version-one
tombstone while retaining the schedule and holiday ciphertext. The UI showed the
regular day shift and the independent holiday.

With airplane mode enabled and the API's ADB reverse mapping removed, a force-stop
and cold start reopened Home and the work calendar. Stored document hashes and
the published generation were unchanged. Reconnection then acquired an overnight
roster at version two on the same retained head; the UI showed its next-day end
time. No pending generation remained, and the inspected Android crash buffer
contained no HRIS entry. Calendar screens at 200% text were inspected.

The fixture services and emulator were stopped with AVD data retained. This change
contains HRIS-specific sync policy and does not require a starter foundation port.
The standard WireGuard APK was rebuilt and its signature verified. Its archive
payloads match the validated standard build. The phone's ADB endpoint did not
respond, so no phone installation was performed in this increment.

## Leave read admission

On 16 October 2026, root checks passed with 1,550 desktop/JVM/UI tests. The Leave
domain, data, presentation and assembled application Chromium suites passed 210
tests. Android dev assembly and the production Web distribution passed. Regression
coverage includes failed denial persistence and fact cleanup, replacement routes,
partial bootstrap, newer denials during acquisition, late account/company results,
cancelled writes, authenticated storage and command recovery after response loss.

An API 35 emulator used an isolated API/PostgreSQL/worker and disposable accounts.
It acquired an actual balance, submitted request and activity. A loopback test proxy
then injected `403 / mfa_required` on Leave reads. Detail closed, and the separate
encrypted denial remained after ordinary facts were removed. With airplane mode
enabled and ADB forwarding removed, a cold start and a new Leave route kept reads
closed. The stored denial generation was unchanged.

After the proxy returned normal responses, a complete acquisition restored six
encrypted documents and removed the denial. Another offline cold start reopened
Home, balances, request dates, reason and activity. All six ciphertext hashes and
the published generation were unchanged by those reads. Screenshots were inspected.
The inspected crash buffer contained a UIAutomator registration failure from
overlapping captures; subsequent captures ran sequentially. No HRIS application
fatal exception was recorded. Emulator connectivity was restored, and the owned
fixture services and emulator were stopped with application data retained.

The WireGuard APK was retained separately from the loopback acceptance build. The
phone's ADB endpoint did not respond, so it was not installed in this increment.
Standard development services were unchanged. This correction contains Leave
policy and storage behavior; it does not require a starter foundation port.

### Leave admission APK delivery

After notice, the validated dev APK from `781604e4` was installed on the authorized
Android 16 phone on 16 October 2026 at 21:52:30 WIB. ADB reported success, and
package metadata confirmed the update. The APK is 29,790,320 bytes with SHA-256
`49f7c461d5d22fc88873e161b9d260130b671c6449ef3f8abe4352d2aa079c76`.
The installation used `--skip-build --install-only`; the phone application was
not launched or driven. This APK includes the preceding read-admission and
calendar improvements; the unfinished-input changes under development were not
part of that installation. Standard development services were not changed during
this delivery.

## Leave working copies

On 16 October 2026, root checks passed with 1,576 desktop/JVM/UI tests. The Leave
domain, data, presentation and assembled application suites passed 236 tests in
Chromium. The browser run alongside Android compilation disconnected during
startup; the separate run with one worker passed without changing test deadlines.
Android dev assembly and production Web distribution passed. The initial Web
optimizer was terminated under memory pressure; the separate build used one
Binaryen thread and a 1.5 GiB Gradle heap, retaining the normal optimization and
existing bundle warnings.

Regression coverage includes offline input after credential expiry, exact evidence
metadata, authenticated account/company/employment storage, competing edits,
shared key creation, cancelled encryption, unknown local-save outcomes, coalesced
edits during pending writes, Back/stop/disposal, and a denial during a pending save.
Submission tests retain the original working revision and command, preserve newer
input during confirmed cleanup, and keep recoverable commands after cleanup fails.
The assembled application reopened unfinished dates, portions and reason offline
before reviewing evidence and recovering one submission after response loss.
Normal and 200% text screenshots were inspected; Back and the fixed submit action
remained reachable.

API 35 acceptance used an isolated API/PostgreSQL/worker and disposable accounts.
The native form retained its leave type, date range, portion and reason after
Back and a process restart. With connectivity disabled and the emulator clock
advanced by 73 hours, a cold start reopened the same input. An offline edit changed
only the encrypted working copy; acquired fact ciphertext and its published
generation remained unchanged. Another cold start restored that edit. The SQLite
snapshot did not contain the plaintext reason.

After restoring time and connectivity, an explicit retry acquired the catalog
without replacing the input. Review excluded the non-working date and charged
half a day. Submission produced exactly one pending request, one activity entry
and one allocation in the isolated server. Confirmation removed the matching
working copy and retired the command. A new form contained no submitted input;
explicit discard removed that new copy while retaining the submitted request.

Native screenshots were inspected in dark mode at 200% text, including the discard
confirmation. No HRIS fatal exception was recorded. Clock, connectivity and display
settings were restored; the owned services and emulator were stopped with AVD data
retained. Attachment selection was covered by automated contracts, not by this
native fixture, which had document storage disabled. This behavior is specific to
HRIS Leave and does not require a starter foundation port.

After notice, the validated WireGuard dev APK was installed on the authorized
Android 16 phone on 16 October 2026 at 23:08:56 WIB. ADB reported success and package
metadata confirmed the update. The 29,856,155-byte APK has SHA-256
`644a062f4c518fdc801a285915d3976530987cad323b3b823a48bf66e617ce63`.
Installation used `--skip-build --install-only`; the phone application was not
launched or driven. Standard development services were unchanged by this delivery.

### Leave form lookup in CI

The subsequent Linux CI run could not find the Reason field before scrolling:
the lazy list had not composed that off-screen item. Apple and Windows jobs
passed. The layout test now scrolls the list to the field's semantics, asserts
that it is displayed, then edits it. It retains the calculation, submission and
fixed Back assertions. Root checks passed again with 1,576 desktop tests after
this test correction. Application implementation did not change, so this increment
did not rebuild or reinstall the APK.

### Overtime read admission

Confirmed Overtime restrictions now apply to overview, request and command reads
through a separate encrypted admission record. Regression tests cover failed
cleanup, failed or cancelled admission writes, replacement routes and sessions,
late results, command preservation and a newer denial during older acquisition.
SQLite and IndexedDB contracts verify encrypted ownership and revision checks.
MVI tests verify that a read observer cannot cancel the operation persisting its
own denial, that disposal still cancels recovery, and that later company denials
request a workspace recheck. A scope-rejected cursor recovers through staged
replacement even when ordinary fact cleanup fails.

Root checks passed with 1,603 desktop/JVM/UI tests and the 70-module architecture
check. The selected Workforce and assembled-app Chromium suites passed all 250
tests. Android dev assembly passed. A restriction whose storage write fails is
retained for the process lifetime; only a persisted record survives process death.
Admitted local facts retain their existing age-independent read behavior.

API 35 acceptance used an isolated API/PostgreSQL/worker with a loopback fault
proxy. A real request remained readable after an offline cold start. Injecting a
403 `mfa_required` response closed its detail and actions. The encrypted denial
survived another offline cold start and retry without changing its stored revision.
After admitted synchronization, overview and detail reopened. Planning and actual
time forms resolved their production dependencies; the actual-time form also
recovered from an injected denial. A further offline cold start retained the same
three encrypted records and active generation. No request was submitted or changed
by this acceptance run.

Dark appearance at 200% system text was inspected, including the denied and
recovered screens. No HRIS fatal exception was recorded in the completed run.
Connectivity was restored and the owned services and emulator were stopped with
AVD data retained. The initial emulator attempt stalled under an overly restrictive
memory limit; acceptance completed after adjusting that limit. The local Web
optimizer was killed by the host OOM handler; production Web subsequently passed
in [GitHub Actions](https://github.com/fajarxfce/hris-mobile/actions/runs/38943575491),
alongside Linux verification, Android variants, Windows and Apple builds. The Web
optimizer was not repeated on the shared VPS. Kotlin Web compilation and the
selected Chromium tests had also passed locally.

### Overtime planning drafts

Unfinished planning input now has an encrypted employment-owned working copy,
separate from synchronized requests and immutable commands. New tests cover
incomplete input after three offline days, token renewal, replacement sessions,
read denial, cancellation, competing edits, lost save acknowledgements and cleanup
that preserves newer input. SQLite and IndexedDB share the storage contract.
MVI cases cover bounded autosave, Back/Stop flushing, disposal, recovery and waiting
for local durability before submission.

Root checks passed with 1,631 desktop/JVM/UI tests and the 70-module architecture
check. The selected Chromium suites passed all 278 tests. The assembled application
resolved the production bindings, and the Indonesian form was inspected at 200%
text in dark appearance; its shortened title keeps words intact. Browser tests ran
in a separate bounded Gradle process after memory pressure interrupted the first
combined run. Production Web optimization remains assigned to GitHub CI.

Android dev assembly passed. API 35 acceptance used an isolated API, PostgreSQL
and worker. With API forwarding removed and Wi-Fi/mobile data disabled, a cold
start restored Home and the unfinished plan: start time, empty end time, empty
break and reason. Its encrypted records and generations were unchanged. An
offline edit changed only the working copy and survived another process restart;
neither reason appeared as plaintext in the copied database.

After reconnection and current schedule review, submission created exactly one
plan with the restored reason, 18:30–20:30 interval and 15-minute break. Canonical
confirmation removed its working head and retired the command. The detail page
was inspected in dark appearance at 200% system text. The completed run recorded
no HRIS crash; original device settings were restored and the owned services,
database and emulator were stopped. This does not measure frame time or memory
improvements.

The validated WireGuard APK was installed on the authorized Android 16 phone
with `--install-only`; no phone UI was opened or operated. Its SHA-256 is
`65662b5bcced73bb5b61867464c7e9e7c2e3c973ad97aa2032e9a1c42ae04c8f`.

### Overtime actual-time and withdrawal drafts

Actual-time and withdrawal input now has separate encrypted working copies for
each owned request and action. New domain, storage and MVI tests cover unfinished
input after three offline days, token renewal, replacement sessions, read denial,
cancellation, competing editors and lost save acknowledgements. Submission pins
the acknowledged input and original request version. Confirmation removes only
its matching draft; newer edits and the other action's draft remain intact.
Failed cleanup retains the confirmed command for recovery without redelivery.

Root checks passed with 1,664 desktop/JVM/UI tests, the 70-module architecture
check, formatting and static analysis. The selected Chromium suites passed all
311 tests, including the IndexedDB contract. Android dev assembly passed. The assembled
application resolved the new production bindings. Actual-time layout was inspected
in Indonesian at 200% text in dark appearance; the withdrawal form, fixed actions
and local save status were also inspected.

API 35 acceptance used an isolated API, PostgreSQL and worker. With Wi-Fi/mobile
data disabled and API forwarding removed, a cold start restored Home and both
action drafts. Actual time retained 08:30–11:00, an empty break and its reason;
withdrawal retained a separate reason. Encrypted records and checkpoints were
unchanged. An offline edit changed only the actual-time working copy and survived
another restart. The reasons were absent as plaintext from database copies.

After reconnection, entering a 15-minute break and submitting recorded exactly
one actual-time change: 135 minutes and request version 1. Canonical confirmation
removed only that draft. The withdrawal draft still retained version 0 and its
input, with editing disabled after the server change. Explicit deletion followed
by a new withdrawal recorded exactly one further change, at version 2. No action
working heads or retained commands remained after canonical confirmation.

The restored, changed and confirmed screens were inspected in dark appearance
at 200% system text. No HRIS crash was recorded. Device settings were restored;
the owned API, worker, database, proxy and emulator were stopped and their cleanup
was verified.

The validated WireGuard APK was installed on the authorized Android 16 phone
with `--install-only`; no phone UI was opened or operated. Its SHA-256 is
`a7a6b47daf86bd9b4fa6c173ee6ebb30abd6fd898db2085f697b9933a7ec1c78`.

### Shared Inbox read admission

Inbox list, detail, unread counts and retained actions now share an encrypted
read-admission record. Root checks passed with 1,679 desktop/JVM/UI tests and
four build-convention tests, architecture checks, formatting and static analysis.
All 67 selected Inbox Chromium tests passed, including the IndexedDB admission
contract. Android dev assembly passed for the WireGuard and loopback endpoints.

API 35 acceptance used an isolated API, PostgreSQL and worker. The native app
loaded a published message and its unread count. A local fault proxy supplied
`sync_scope_changed`; reads closed while the original encrypted message remained
unchanged and a replacement generation staged. After Wi-Fi/mobile data were
disabled and API forwarding removed, a cold start restored Home. Inbox retained
its denial through reopening and explicit retry, with identical documents,
checkpoints and encrypted admission metadata.

Reconnection completed acquisition and reopened the message. Mark as read then
confirmed version 1 on the server, cleared the unread badge and retired its local
command after canonical confirmation. No admission record or staging generation
remained. The denied and recovered screens were inspected in dark appearance at
200% system text. No HRIS crash was recorded. Device settings matched their
original values, and the owned API, worker, database, proxy and emulator were
stopped with cleanup verified.

The validated WireGuard APK was installed on the authorized Android 16 phone
with `--install-only`. Its installed SHA-256 matched
`f4628513a7c2332eefb4bd539e87b0ab3cb79cf2392b5d593883a7944297b41d`.
No phone UI was opened or operated.

### Company data synchronization

Account provides [Data and sync](data-sync.md) for nine company datasets. Root
checks passed with 1,706 desktop/JVM/UI tests and four convention tests, including
architecture checks for 70 modules, formatting and static analysis. Each feature
owns its status/admission and shares acquisition with its registered worker.

Thirteen MVI tests cover local entry/return, published timestamps, individual and
bounded combined refresh, partial completion, failed storage recovery, confirmed
read restrictions, queued cancellation, late results and disposal. Repeated retry
during a non-cancellable storage callback exposed overlapping readers; the
regression now verifies one active reader and rejection of its cancelled result.

The assembled application test opens the page from Account, cancels a real
fixture request, retries it and returns to the selected tab. Three layout tests
verify fixed navigation and reachable actions at 200% text, busy-action bounds,
localized failures and Indonesian dark appearance. Their screenshots were
inspected.

All 879 selected feature domain/data Chromium tests passed, including metadata
queries against IndexedDB. The 13 new Home MVI browser tests passed in a separate
run with unchanged source after the combined run lost its Karma connection on
that final target. Android dev assembly passed for loopback and WireGuard; both
APKs passed signature/package/API-target checks and have identical source inputs.

API 35 acceptance used an isolated API, PostgreSQL and worker. All nine datasets
completed acquisition. With Wi-Fi/mobile data disabled and API forwarding removed,
a process restart restored Home and the saved synchronization times. Explicit
offline refresh retained the same encrypted records, checkpoints and generations.

A confirmed Inbox MFA restriction hid its status and survived another offline
process restart and retry. Reconnection and admitted acquisition restored the
Inbox timestamp and cleared its restriction. Other datasets retained their saved
status. Synchronization issued no business writes or pending commands.

The recovered page was inspected in dark appearance at 200% system text. The
final dataset action remained visible after scrolling, and the fixed Back action
returned to Account. No HRIS crash was recorded. Emulator settings were restored;
the owned API, worker, database, proxy and emulator were stopped with cleanup
verified.

The validated WireGuard APK was installed on the authorized Android 16 phone
with `--install-only`. Its installed SHA-256 matched
`c6ab106243cbf591640222588477474a6ca9733e3ab3c45b16f487eae3e1adfc`.
No phone UI was opened or operated.
