# Template updates

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
