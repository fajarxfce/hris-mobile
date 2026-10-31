# Mobile layout review — 31 October 2026

The user requested a complete layout/component review of the employee mobile app,
including lists, details, creation/editing and supporting pages, plus fictional
sample data for the already linked development account. Keep the Fluent visual
language. This is a new active task after the completed feature audit.

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
- [ ] Inspect actual rendered before/after screenshots; verify narrow screens,
  Indonesian, large text, dark appearance, keyboard and fixed action reachability.
- [ ] Required Mobile checks/builds, proven reusable Starter port and independent
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
the old informational default. Only these components, their portable large-text
interaction regression and usage guidance belong in Starter. Session/profile,
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

## Verification in progress

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
- Native release verification, final screenshots, Starter acceptance evidence
  and install-only delivery are still pending.
- Starter independent root checks, Android dev assembly and production Web build
  passed in 3m55s for the portable components.

Initial before captures and fresh Android/Compose captures are retained locally in
`.work/ui-review-2026-10-31/`. A selected fictional gallery and final measurement
record will be committed with delivery. No physical iPhone test is claimed.
