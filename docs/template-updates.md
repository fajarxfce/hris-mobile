# Template updates

## Apple Firebase authentication

The password/bundle increment from Mobile `196c1171` passed all 82 simulator
cases and Xcode in run `40855843460`, then ported to Starter `8a75de5`. Starter
root/Android checks, 308 desktop and 31 identity Chromium cases passed; its own
Apple verification is pending.

The later Mobile `43cd8ec6` increment also owns Google system-browser
PKCE, callback correlation and code exchange. The shared callback session accepts
either an Android ID token or an Apple authorization code, closes its native UI
before transport and rejects late or mismatched proof. Google OAuth uses the
existing OIDC 0.18.4 crypto dependency directly; versions and verified artifacts
are unchanged. Port the generic provider source/tests, iOS host wiring, identity
native CI targets and catalog entry only after Mobile Apple validation passes.
Keep HRIS native exchange/session policy and environment configuration here.

## Actual Android FCM acceptance

The 29 October [real-provider case](validation.md#actual-fcm-foreground-and-background-delivery)
passed foreground/background delivery, generic OS presentation, Inbox navigation
and retirement on unchanged Mobile `678783b3`. The supplied server credential and
owned acceptance driver belong to Platform. No reusable source change was needed;
there is no new Starter port. Existing queue/scope regressions and other platform
provider acceptance keep their separate evidence.

## Apple CI compilation budget

Mobile `451a2c4d` separates cold iOS test compilation, simulator execution and
unsigned Xcode compilation into bounded steps (25/15/30 minutes; job 75). Manual
`scope=apple` has independent concurrency, while ordinary/default runs retain
all targets. Simulator reports are retained even on failure. The original Mobile
Apple job timed out during compilation; follow-up
[40817592306](https://github.com/fajarxfce/hris-mobile/actions/runs/40817592306)
passed 27 simulator tests, the full Xcode build and report upload before porting.

Starter port `1817e45` passed its independent
[Apple run 40820482699](https://github.com/fajarxfce/compose-fluent-starter/actions/runs/40820482699):
27 simulator tests in ten suites, full unsigned Xcode build and report upload.
Local root checks retained 306 desktop cases, ten runner and six distribution
regressions. It preserves the FluentStarter project/scheme and existing three
jobs (Web remains in verify). No application source, HRIS configuration or business
policy was ported. See [CI evidence](validation.md#apple-ci-cold-compilation-budget).

## Native upload/review acceptance

The 29 October real API/storage/Android recovery fixture required no Mobile source
change. Existing upload transport, encrypted checkpoints, pending counts, inspection
review and cancellation passed together. No shared foundation correction or Starter
port is due. See [validation](validation.md#interrupted-upload-and-inspection-review-on-android).

## HRIS registration recovery

The 29 October native acceptance fixes registration expiry precision and cleanup
of an absent previously owned HRIS session family. Both the registration API
adapter and reconciliation policy exist only in HRIS Mobile. Generic Firebase SDK
acquisition/deletion, permission and notification adapters are unchanged. Root,
Chromium and actual SDK/API/emulator recovery passed; no Starter port is needed.
See [the acceptance evidence](validation.md#firebase-registration-and-offline-recovery-on-android).

## Firebase provider foundation

Mobile `d191c61d` ports to starter `9a13791`: 20 provider source/test files plus
host wiring and two starter-container regressions. The provider owns Android
Credential Manager interactions and bounded Firebase REST calls without retaining
a Firebase SDK session. App-neutral comments and fixture IDs are the deliberate
source differences. HRIS discovery, approved binding, session exchange, MFA and
login screens stay in Mobile.

Mobile passed 1,837 desktop/UI and 119 related Chromium tests, Android assembly and
real Firebase email/password through HRIS MFA on its dedicated Android emulator.
Google system UI launch/dismissal passed, without Google token acceptance. The
starter passed 306 desktop/UI tests, 29 identity-data Chromium tests and configured
Android assembly. All new dependency hashes match the validated Mobile metadata.
See [Firebase authentication](firebase-auth.md) and [validation](validation.md).

## Notification resource paths

Mobile `b77d991f` ports to starter `7562e67`. Four source/test files match after
checking source baselines. The adapter validates bounded internal destinations,
appends individual Android URI segments, and replaces notifications using the full
event ID with one alert. Both repositories passed root checks, all eight
notification-data Chromium tests and configured Android dev assembly; starter
passed 291 desktop tests. HRIS queues, authorization and canonical Inbox acquisition
stay in this application.

## Incoming link ownership

Starter `29081c5` ports the session-bound pending-link state, matching
acknowledgements, and navigation that waits for both the resumed host and entered
destination. Ownership and cold-login/background regressions pass in both
applications. Mobile root checks passed with 1,732 desktop tests; starter passed
286. Their app Chromium suites passed 17 and three tests, respectively, and both
Android dev assemblies passed.

HRIS account/company resource matching, Inbox routes and company admission stay
in Mobile. Provider push delivery and physical URL acceptance are separate work.

## Published partition pages and complete command observation

Mobile `0428aa46`, ported as starter `8707dec`, adds metadata-only pagination across published partitions and
complete command observation under the existing 128-command account capacity.
The twelve database source/test files were copied to the starter only after their
SQLite and Chromium contracts passed in Mobile, with matching source baselines
checked before copying. Starter root checks (284 desktop tests), its 25 database
Chromium tests and Android dev assembly passed on 28 October 2026.

HRIS pending counts, encrypted draft mapping, admission policy and Account UI stay
in this application. See [pending actions](pending-actions.md),
[replication storage](replica-storage.md) and [command storage](command-storage.md).

## Published replica metadata

The shared replica store can observe the visible checkpoint without reading
resource payloads. An incomplete replacement retains the previous published
metadata; publication, ownership changes and removal update it atomically.
Repositories still own decryption, and feature use cases still own admission.
The same implementation and portable regression are in
[Compose Fluent Starter](https://github.com/fajarxfce/compose-fluent-starter/blob/main/docs/replica-storage.md).

Both repositories passed root checks, all 25 database Chromium tests and Android
dev assembly. The regression covers staging, publication, account/company
isolation, replacement login and observer cancellation. This capability alone
does not report worker execution or confirm delivery of local actions.

## Filter labels at large text sizes

`AppFilterChip` now accepts an optional `maxLines` while retaining its single-line
default. The component and regression test also exist in Compose Fluent Starter.
The test keeps a complete month and year visible between 48 dp navigation actions
at 320 dp width and 200% text. Attendance and Overtime select two lines; their
month selection and data ownership remain in HRIS Mobile.

Both repositories passed root checks and Android dev assembly. Starter checks
included 284 desktop tests. HRIS also verified an offline Android upgrade and the
wrapped month label at 200% text.

## Bounded protocol response bodies

The public `readApiBody` boundary and its regressions also exist in
[Compose Fluent Starter](https://github.com/fajarxfce/compose-fluent-starter/blob/main/core/network/src/commonMain/kotlin/dev/fajar/starter/network/ApiResponseBody.kt).
Feature-owned HTTP protocols can select a bounded success-body budget while
structured HTTP problems retain their independent 16 KiB limit. Invalid budgets
fail before acquisition; callers own and clear successful buffers. The expense
receipt protocol, authorization and export workflow remain in HRIS Mobile.

## Enum route arguments

The shared `enumNavType` adapter also exists in
[Compose Fluent Starter](https://github.com/fajarxfce/compose-fluent-starter/blob/main/core/presentation/src/commonMain/kotlin/dev/fajar/starter/presentation/navigation/EnumNavType.kt).
It persists enum names on every Navigation target, rejects unknown values and
preserves argument equality when rebuilding the graph. Three new regressions cover
saved-state round trips, invalid values and graph identity. Both repositories passed
root checks, shared-presentation Chromium tests, Android dev assembly and application
Web compilation. HRIS overtime routes and action policy remain in this application.

## Controlled time input

The `AppTimeInput` introduced with
[overtime planning](https://github.com/fajarxfce/hris-mobile/commit/a66c787) also
exists at [63ff872](https://github.com/fajarxfce/compose-fluent-starter/commit/63ff872).
It keeps numeric editing and accessible labels reusable while the consuming form
owns validation and timezone policy. The starter passed 268 desktop tests, Android
dev assembly and Web compilation; all three CI jobs passed. HRIS planning passed
887 desktop tests, 120 Workforce Chromium tests, Android dev assembly, Web
compilation and real-API emulator acceptance.

## Page actions and progress

The fixed page action slot, labelled progress ring, busy icon behavior and two
shared UI regressions also exist at
[b481f65](https://github.com/fajarxfce/compose-fluent-starter/commit/b481f65).
Both repositories passed root checks, app Chromium tests and Android dev builds.
HRIS company selection and operation state stay in this application.

## Navigation restoration

Scoped navigation persistence, deferred graph attachment and four process/scope
restoration regressions also exist at
[9248f59](https://github.com/fajarxfce/compose-fluent-starter/commit/9248f59).
Both repositories passed root checks, app Chromium tests and Android dev builds.
HRIS session admission and company policy remain application responsibilities.

## Owned file output

The platform save dialogs, bounded writers, handle ownership and error mapping
also exist at [cf57982](https://github.com/fajarxfce/compose-fluent-starter/commit/cf57982),
with the Apple verification-handle correction at
[d1cb257](https://github.com/fajarxfce/compose-fluent-starter/commit/d1cb257). Both
repositories passed root checks, file-I/O/localization/app Chromium tests and
Android/Web builds. HRIS content admission, encrypted storage and export workflow
remain here.

## Binary transport

The bounded range reader and shared API response boundary also exist in
[Compose Fluent Starter](https://github.com/fajarxfce/compose-fluent-starter/blob/main/docs/file-transfers.md).
Both repositories passed root checks, network and transfer Chromium tests, and
Android dev assembly. The reusable transport validates range responses and retains
structured API failures; HRIS document admission and storage policy remain here.

## Scheduler registration

Deferred platform scheduler acquisition and its generated-DI regression also exist
at [dfcef60](https://github.com/fajarxfce/compose-fluent-starter/commit/dfcef60).
Tasks can request subsequent work without recursively constructing their scheduler.
Both repositories passed root checks, sync data Chromium tests and Android dev
assembly. HRIS-specific upload state and delivery policy remain in this application.

## Owned file input

The OS picker adapters, bounded reads, incremental hashing and file failure
boundary also exist at
[41b76c8](https://github.com/fajarxfce/compose-fluent-starter/commit/41b76c8).
Apple file-handle cleanup also exists at
[0e0e644](https://github.com/fajarxfce/compose-fluent-starter/commit/0e0e644).
Both repositories passed root checks, 12 file-input Chromium tests and Android
adapter compilation. HRIS passed 642 desktop tests and the starter passed 241.
Company admission, document encryption and the HRIS transfer protocol remain
specific to this application.

The finite IndexedDB test deadline and verified Android runtime metadata are also
at [0a7c934](https://github.com/fajarxfce/compose-fluent-starter/commit/0a7c934)
and [eb74538](https://github.com/fajarxfce/compose-fluent-starter/commit/eb74538).
Both repositories passed root checks, 23 database Chromium tests and Android
file-input dependency checks. Mobile passed 660 desktop tests and an Android dev
build; the starter passed 241 desktop tests.

## Retained tab ownership

The navigation ownership guidance and a regression guarding the starter's
existing retained dashboard behavior are at
[f3389dd](https://github.com/fajarxfce/compose-fluent-starter/commit/f3389dd).
Its root checks passed with 230 desktop tests. Home attendance, company admission
and employee-directory acquisition remain HRIS-specific. Mobile root checks and
the Android dev build passed; emulator checks verified loaded tabs while offline.

## Bounded Android tooling

The optional `--install-timeout` deadline and its regressions also exist in
[Compose Fluent Starter](https://github.com/fajarxfce/compose-fluent-starter/blob/main/tool/run_android.py).
The CLI accepts 30–900 seconds, retains the 180-second default and does not retry
or launch after timeout. Invalid deadlines fail before ADB discovery. Both
repositories passed all ten runner tests; no application build is changed.


The runner's ADB deadlines, validated optional reverse port, forwarding before
installation and seven regression tests also exist at
[8244a00](https://github.com/fajarxfce/compose-fluent-starter/commit/8244a00).
Both repositories passed root checks. The private HRIS VPN endpoint and its
development HTTP policy remain application configuration.

## Durable file storage

The account-owned block store, immutable completion proofs, revision-checked
checkpoints and SQLite/IndexedDB regressions also exist at
[75f5970](https://github.com/fajarxfce/compose-fluent-starter/commit/75f5970).
Both repositories passed root checks, all 23 database Chromium tests and
Android/production Web builds. File encryption and HRIS document policy remain
separate consuming boundaries.

## Inline loading

The compact loading option and its bounded 200% text regression also exist at
[b0af770](https://github.com/fajarxfce/compose-fluent-starter/commit/b0af770).
Both repositories passed root checks and Android/production Web builds. Leave
form state and delivery outcomes remain in this application.

## Date selection

The controlled calendar and its disabled-date, accessible-label and 200% text
regression also exist at
[3388304](https://github.com/fajarxfce/compose-fluent-starter/commit/3388304).
Mobile and starter root checks and Android builds passed; starter production Web
and mobile Web compilation also passed. Linux, Windows and Apple CI passed in
both repositories. Leave date/portion policy and server preview remain here.

## Multiline text fields

Optional multiline input and its 200% text regression from
[844314c](https://github.com/fajarxfce/hris-mobile/commit/844314c) also exist in
[Compose Fluent Starter f63b94f](https://github.com/fajarxfce/compose-fluent-starter/commit/f63b94f).
Both repositories passed root checks and Android/Web builds. The field retains
its single-line default; Leave validation, dialog state and durable actions stay
in HRIS Mobile.

## Storage observer recovery

The completed-source recovery implemented in `eee789e` was ported to
[Compose Fluent Starter 60714cf](https://github.com/fajarxfce/compose-fluent-starter/commit/60714cf).
Its dashboard reopens the owned content subscription after successful writes and
rejects cancelled results before publishing state. The regression first reproduced
the disconnected source; root checks with 215 desktop tests, three dashboard
Chromium tests and Android/Web builds then passed. Leave policy and transactions
remain in this application.

This application was generated from
[Compose Fluent Starter](https://github.com/fajarxfce/compose-fluent-starter).
Template generation does not automatically synchronize either repository.

When application development exposes a reusable issue in the foundation, add a
regression test and port the correction back to the starter. This includes MVI,
networking, storage, synchronization, lifecycle, reusable Fluent components, and
build conventions. Validate the correction in each affected repository and
record the counterpart commit. Keep HRIS domain rules, API adapters, company
policy, deployment configuration, credentials, and personal data here.

Apply upstream improvements through reviewed atomic commits. Prefer a clean
cherry-pick when the code still matches; adapt deliberately when it has diverged.
Do not replace this application's tree with a new template snapshot or merge
unrelated histories. Unit, backend, build, and device validation are distinct.

Bootstrap route state ownership and its pending-failure/retry UI regression also
exist in the starter at
[`038d516`](https://github.com/fajarxfce/compose-fluent-starter/commit/038d516).
Both repositories passed their root checks and Android dev builds. The company
availability adapter and policy remain specific to HRIS Mobile.

The Android runner's explicit-device installation and install-without-launch
behavior also exist in the starter at
[`67dd7aa`](https://github.com/fajarxfce/compose-fluent-starter/commit/67dd7aa).
Both repositories passed their root checks and four runner tests. Emulator
configuration and HRIS backend forwarding remain in this application.

Android core library desugaring also exists in the starter at
[`5e3967c`](https://github.com/fajarxfce/compose-fluent-starter/commit/5e3967c).
The API 24 emulator first reproduced a missing Java time class, then passed the
calendar and offline-restoration checks with the correction. API 35 also passed.
Both repositories passed root checks and Android/Web builds.

Date-only formatting and its English/Indonesian timezone regression also exist at
[`ddc73ac`](https://github.com/fajarxfce/compose-fluent-starter/commit/ddc73ac).
HRIS calendar policy, encrypted snapshots and Web IANA timezone initialization
remain in this application. Apple compilation is checked by platform CI; physical
iOS acceptance is separate.

Compact screen headers, single-action tiles, page spacing and their large-text/
keyboard regression also exist at
[`8b8d625`](https://github.com/fajarxfce/compose-fluent-starter/commit/8b8d625).
Both repositories passed root checks and Android/Web builds. Employee navigation,
company information, inbox and calendar layout changes remain in HRIS Mobile.

Foreground location and permission adapters, callback ownership, failure mapping
and cancellation regressions also exist at
[`694f244`](https://github.com/fajarxfce/compose-fluent-starter/commit/694f244).
The Core Location protocol bridge correction also exists at
[`f9c0377`](https://github.com/fajarxfce/compose-fluent-starter/commit/f9c0377).
Optional card content spacing exists at
[`d7e7680`](https://github.com/fajarxfce/compose-fluent-starter/commit/d7e7680).
Both repositories passed root checks and Android/Web builds. Apple callback
regressions run in simulator CI. Attendance admission, location evidence policy,
encrypted commands and employee navigation remain in HRIS Mobile.

Fixed page bars, adaptive menu grids, text actions and preference rows also exist
at [`f90abb1`](https://github.com/fajarxfce/compose-fluent-starter/commit/f90abb1).
Both repositories passed root checks and Android/Web builds. Shared UI regressions
cover fixed actions during scrolling, 200% text, keyboard activation and preference
semantics. Home attendance, capture recovery, company switching and service
admission remain specific to HRIS Mobile.

## Root navigation

Default-tab Back handling, the returning-root transition guard and single-line
navigation labels also exist at
[`6daa66e`](https://github.com/fajarxfce/compose-fluent-starter/commit/6daa66e).
Both repositories passed root checks and Android/Web builds. Shared regressions
cover inner-page return, cancelled gestures, rapid Back, platform fallback and
full accessible names at 200% text. HRIS Mobile passed 393 JVM/UI tests; Android
emulator checks also cover the four tabs, directory search, separate All services,
English/Indonesian and light/dark layouts. The validated APK was installed on the
connected phone without launching it. Company directory policy, Inbox integration,
Home content and service admission remain specific to this application.

## Search and filter controls

The company directory uses reusable `AppSearchField`, `AppFilterChip` and
`AppDialog` components. These controls and their keyboard, clear, selection,
dismissal and large-text regressions were ported to Compose Fluent Starter.
Employee queries, department lookup, access policy and MVI remain in HRIS Mobile.
The starter passed its root checks and Android dev build for this update.
The mobile foundation is [fcbac8e](https://github.com/fajarxfce/hris-mobile/commit/fcbac8e).

## Replication metadata and navigation badges

Checkpoint-only observation and its SQLite/IndexedDB contract tests also exist at
[`daaed3f`](https://github.com/fajarxfce/compose-fluent-starter/commit/daaed3f).
Adaptive navigation badge slots, `AppCountBadge` and large-text/accessibility
regressions exist at
[`f1437c7`](https://github.com/fajarxfce/compose-fluent-starter/commit/f1437c7).
Both repositories passed root checks, the database Chromium tests and the Android
dev build. Account unread totals, Inbox cache interpretation, and the badge's
scope/lifecycle policy remain in HRIS Mobile.

## Month selection

The localized, controlled month picker, optional filter-chip icons, and `formatMonth`
also exist at [86235b6](https://github.com/fajarxfce/compose-fluent-starter/commit/86235b6).
The mobile foundation is [f531200](https://github.com/fajarxfce/hris-mobile/commit/f531200).
Both repositories passed root checks, localization Chromium tests, Android dev
and production Web builds. Shared tests cover disabled months/years, 200% text,
English/Indonesian names and timezone boundaries. Monthly attendance, access
revalidation, encrypted history and capture recovery remain in this application.

## Content categories

The controlled `AppTabs` and its accessibility/large-text regressions also exist
at [fd858ae](https://github.com/fajarxfce/compose-fluent-starter/commit/fd858ae).
The mobile foundation is [c992f95](https://github.com/fajarxfce/hris-mobile/commit/c992f95).
Both repositories passed root checks, application Chromium tests and Android dev
assembly. Document categories, navigation and transfer policy remain in HRIS Mobile.

## Dedicated search controls

`AppSearchEntry`, optional initial focus in `AppSearchField`, and their keyboard,
semantics and disposal tests also exist at
[da8acb2](https://github.com/fajarxfce/compose-fluent-starter/commit/da8acb2).
Both repositories passed root checks, application Chromium tests and Android dev
assembly. Employee routes, query/filter ownership, company access and transport
remain in HRIS Mobile.


## Stable read ownership

`ReplicaScope`, composite owner keys, Room/IndexedDB migrations and their shared
regression contract are also present in [Compose Fluent Starter c199bab](https://github.com/fajarxfce/compose-fluent-starter/commit/c199bab). Session changes
hide account-owned records without deleting them, and stale request guards cannot
read or mutate a replacement session's data. Temporary session projections retain
their explicit cleanup behavior. Storage rules distinguish local ownership,
freshness, cursor retention and live operation admission.

The starter passed root checks with 279 JVM/desktop tests, 24 database Chromium
cases and Android dev assembly. HRIS passed its own root/browser checks and exercised
the integration on Android. Inbox encryption formats, legacy-read compatibility,
server cursor replacement and company/permission policy remain in HRIS Mobile.

## Indexed replica reads

[Compose Fluent Starter d94c7f3](https://github.com/fajarxfce/compose-fluent-starter/commit/d94c7f3)
contains the matching raw equality indexes, atomic multi-page queries, compatible
Room/IndexedDB migrations and scoped HKDF/HMAC token function. Portable regression
coverage and format/bound checks are shared; Leave contracts and application
synchronization remain in HRIS Mobile. Both workspaces passed root checks,
41 database/secure-storage Chromium tests and Android dev assembly.
