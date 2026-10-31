# Mobile layout review — 31 October 2026

The user requested a complete layout/component review of the employee mobile app,
including lists, details, creation/editing and supporting pages, plus fictional
sample data for the already linked development account. Keep the Fluent visual
language. This review and its compact-filter follow-up are complete.

## Follow-up: compact filters requested by the user

Build 3's date/status filters were still visually heavy. The follow-up groups
previous/next chevrons at the trailing edge, removes filter pill outlines and
uses wrapping text/dropdown actions. Expense/overtime period and status share
one 48 dp row when labels fit; expense creation-month context remains explicit.
Attendance, leave year, payslip year/revision and calendar week use the same
navigation structure. Leave request status sits beside its section heading and
continues to apply to all dates, independently of the balance year.

Installed guidance: [Compose Component Design](https://github.com/chrisbanes/skills/tree/d2f24b4179179f5e8ec8dcfe0527b3619eda1e0f/skills/compose-component-design),
[Compose UI Testing Patterns](https://github.com/chrisbanes/skills/tree/d2f24b4179179f5e8ec8dcfe0527b3619eda1e0f/skills/compose-ui-testing-patterns),
and [UI/UX Pro Max](https://github.com/nextlevelbuilder/ui-ux-pro-max-skill/tree/9a3f14f7423037ccb8feeb4fee7482291b9c820e/.claude/skills/ui-ux-pro-max).
The local `compact filter layout` UX query returned relevant label-reflow and
static-versus-interactive semantics guidance. The Jetpack Compose query returned
flat hierarchy, theme tokens and reusable layout guidance. Existing Fluent
components, dependencies and navigation remain the implementation foundation.

Focused Compose checks passed in 1m38s. The actual recorded images
`apps/shared/build/reports/screenshots/compact-filters-phone.png` and
`compact-filters-large-text.png` were inspected: one row at 390 dp; untruncated
September/year and selected status at 320 dp / 200% text. The final source-matched
root, platform, native-release and Starter checks are complete; current counts
and delivery evidence follow below.

### Configured release evidence

Build **4** is installed on the WireGuard phone and was exercised in the dedicated
Android 35 emulator. Identical sample rows begin **72 dp earlier in Overtime** and
**80 dp earlier in Expenses** than build 3, measured from UIAutomator bounds at
2.75 density. Month/year/week stepping and status selection/reset passed. Dark
200% text preserves full labels; landscape fits period and selected status in one
row. The phone received an in-place install only; only emulator UI was driven.

These galleries show cropped top portions of actual screens (with captions outside
the capture); the landscape capture preserves the whole viewport:

- [Before/after: date and status filters](screenshots/layout-2026-10-31/filters-before-after.jpg)
- [Attendance, leave, calendar and payslip filters](screenshots/layout-2026-10-31/filters-pages.jpg)
- [Dark 200% text and landscape](screenshots/layout-2026-10-31/filters-accessibility.jpg)

The portable test now permits reflow when host fonts require it at 390 dp, while
retaining full-label/non-overlap checks and separately proving one row at 640 dp.
Fresh root checks pass **2,002 Mobile cases / 349 suites** and **331 Starter cases /
114 suites**. The application code and installed APK are unchanged by that test
correction. Scoped CI verification and the unaffected platform jobs passed; production
source is unchanged across the matched runs. See [current measurements](measurements/compact-filters-2026-10-31.json).
The emulator was restored to normal text, light appearance and portrait, its own
session was logged out, and its process was stopped.

## Review and implementation queue

- [x] Shared patterns: labelled selection fields, detail label/value hierarchy,
  consistent full-width sections, status treatment and accessible actions.
- [x] Home, services, account, workspace selection, authentication and settings.
- [x] Inbox list/detail; employees/search/profile.
- [x] Attendance history, calendar, overtime list/detail/plan/actual/withdrawal.
- [x] Leave balances/history/detail/create, evidence and confirmation surfaces.
- [x] Expenses list/detail/draft/item/action, receipts and filter surfaces.
- [x] Documents catalog/record/files/file/upload/download/metadata/history.
- [x] Payslips list/detail; sessions, pending actions, sync and gated states.
- [x] Fictional development records associated with the existing user-linked
  employment, created through authorized APIs with retained idempotency receipts.
- [x] Inspect actual rendered before/after screenshots; verify narrow screens,
  Indonesian, large text, dark appearance, keyboard and fixed action reachability.
- [x] Required Mobile checks/builds, proven reusable Starter port and independent
  validation; push each atomic commit and update continuation evidence.

## Scope and rendered coverage

| Area | Pages and surfaces reviewed | Result |
| --- | --- | --- |
| Entry and navigation | Login, onboarding, verification, company picker, availability/lock gates, Home, services, account, settings | Retain existing stable navigation and preference controls; native login/company/Home/services/account/settings inspected. |
| People and inbox | Employee directory/search/department filter, profile/employment selection, Inbox list/detail/acknowledgement | Read-only field hierarchy, localized profile dates, retained-content refresh, fixed Inbox actions and wrapping confirmation controls. |
| Workforce | Attendance summary/history/day/queue, work calendar, overtime list/detail/plan/actual/withdrawal and date/time pickers | Consistent request cards/status tones and labelled date/time choices. Native populated attendance/calendar/overtime and form screenshots inspected. |
| Leave | Balances/history/status filter, request detail/activity/cancellation, creation/preview/type/portion/date/evidence selection | Status cards, labelled form choices and fixed review/submit placement; preview expiry and command recovery remain guarded. |
| Expenses | Monthly list/status filters, claim/detail/approval/payment, draft/item/receipt selection and submission/withdrawal/recovery | Full-width description, request cards/statuses, fixed creation action, labelled date/category/cost-center choices. |
| Documents | Catalog/classification, local files, upload/file/download, business metadata/details/history, correction editor/list and confirmations | Labelled local tools, title opens detail, explicit download, fixed add/edit/save actions, localized dates and concise read-only labels. |
| Payslips and support | Payslip list/detail/payment history/revisions, sessions, pending actions and data sync | Existing payroll grouping retained; session badge/date hierarchy and non-disruptive refresh; entire pending row opens its workflow. |

All active employee routes were checked against `AppNavigation` and their rendered
fixtures. Unused inherited dashboard/demo pages are not exposed as employee routes.
Populated payroll and restricted/recovery states use fictional UI fixtures; no real
salary/payment was invented merely to fill a screen. Actual Android screenshots
use the dedicated emulator and private development API. No acceptance relies on
a screenshot of an empty page where a populated fixture is available.

## Shared implementation

`AppSelectField` represents a labelled picker with one accessible action and an
untruncated value; date/time choices use appropriate icons. `AppDetailField`
provides a consistent label/value hierarchy. `AppBadgeTone` is additive and retains
the old informational default. These components, the compact-filter foundation and their portable layout/
interaction regressions belong in Starter. Session/profile,
workflow statuses, retained commands and all HRIS routes remain application policy.

## Sample data — complete

Platform `1363abee` records an idempotent authorized-API recipe and verified reads:
four fictional colleagues, three leave requests, three expense claims, three
overtime requests, five attendance corrections, three individually targeted Inbox
announcements and two fictional PDFs. Leave balance examples accompany the records.
The already linked employee sees approved, pending, draft, cancelled and withdrawn
examples. There are no extra sign-in accounts, role grants or real payment actions.

The emulator uses the existing native HRIS credentials for the same fictional
employee. This does not re-enable the deliberately retired Firebase fixture or
change the user's Google binding. The temporary emulator login build excludes
Firebase resources; user delivery must use the normal configured release build.
No user password, token, private operation manifest or real document belongs in Git.

## Earlier build-3 verification

- Initial Mobile root check passed in 3m21s, including 208 shared UI cases.
- A second complete run found eight outdated UI expectations after the document
  and date refinements: five ISO-date expectations, two footer/lazy-list timing
  assumptions and the new test's incorrect translation of the existing Download
  label. The expectations now follow localized displayed values and explicit
  enabled/visible controls; API payload and access-denial assertions remain.
- Final Mobile root check passed in 2m46s: 1998 desktop cases in 348
  suites, zero failures/errors/skips; 208 shared UI cases included. The two
  document interaction checks now wait for admitted save readiness and scroll
  the lazy list to the secondary reset action explicitly.
- Configured `devRelease` build 3 is installed and verified on the authorized
  phone. Its identical APK was exercised on Android 35 with normal Firebase,
  keyboard, Indonesian, dark appearance and system font scale 200%.
- [Measurements](measurements/layout-review-2026-10-31.json) retain APK/signing/API,
  installed-byte proof, test counts and gallery hashes. The local release/Web retry completed successfully in 302.667s with unchanged
  APK bytes. The initial hosted verification found an Inbox test synchronization
  race; `eab6225b` waits for the refreshed projection and passed a new local root
  run. Subsequent source-matched validation of the filter revision is complete; see the current evidence above.
- Starter independent root checks, Android dev assembly and production Web build
  passed in 3m55s for the portable components.

Initial before captures and fresh Android/Compose captures are retained locally in
`.work/ui-review-2026-10-31/`. A selected fictional gallery and final measurement
record will be committed with delivery. No physical iPhone test is claimed.

## Screenshot gallery

These are selected captures, not replacements for the coverage matrix above.
The first gallery retains fictional Compose fixtures from the initial review.
The Android galleries show the configured release APK and the real private API's
fictional records; the accessibility gallery combines an actual Android system
font-scale/dark-mode capture with focused Compose fixtures.

- [Initial fixtures](screenshots/layout-2026-10-31/before-fixtures.jpg)
- [Android lists and details](screenshots/layout-2026-10-31/android-lists-details.jpg)
- [Android forms and keyboard](screenshots/layout-2026-10-31/android-forms.jpg)
- [Large text and dark appearance](screenshots/layout-2026-10-31/accessibility.jpg)
