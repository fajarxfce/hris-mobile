# Mobile layout review — 31 October 2026

The user requested a complete layout/component review of the employee mobile app,
including lists, details, creation/editing and supporting pages, plus fictional
sample data for the already linked development account. Keep the Fluent visual
language. This is a new active task after the completed feature audit.

## Review and implementation queue

- [ ] Shared patterns: labelled selection fields, detail label/value hierarchy,
  consistent full-width sections, status treatment and accessible actions.
- [ ] Home, services, account, workspace selection, authentication and settings.
- [ ] Inbox list/detail; employees/search/profile.
- [ ] Attendance history, calendar, overtime list/detail/plan/actual/withdrawal.
- [ ] Leave balances/history/detail/create, evidence and confirmation surfaces.
- [ ] Expenses list/detail/draft/item/action, receipts and filter surfaces.
- [ ] Documents catalog/record/files/file/upload/download/metadata/history.
- [ ] Payslips list/detail; sessions, pending actions, sync and gated states.
- [ ] Fictional development records associated with the existing user-linked
  employment, created through authorized APIs with retained idempotency receipts.
- [ ] Inspect actual rendered before/after screenshots; verify narrow screens,
  Indonesian, large text, dark appearance, keyboard and fixed action reachability.
- [ ] Required Mobile checks/builds, proven reusable Starter port and independent
  validation; push each atomic commit and update continuation evidence.

## Initial observations

An actual release APK was installed/opened on the dedicated `emulator-5560` for
review. The user's phone remains on build 2. The login screenshot is retained
locally in `.work/ui-review-2026-10-31/`. Existing accepted Compose screenshots
provide the initial populated-page baseline; fresh renders must verify the changes.

The Expense description card shrinks to its text width while adjacent sections
span the page. Form choices currently reuse navigation rows and inconsistent
card wrappers. Creation actions vary between header icons and bottom bars.
Date ranges, secondary identity metadata, request status and activity content
need clearer hierarchy. Authentication/session ownership and business behavior
must remain unchanged by presentation work.

The emulator's previous fictional Firebase binding was deliberately retired when
the user's Google account was linked. Do not re-enable that binding or modify the
user's identity for screenshot access. Use fictional rendering fixtures or a
separate review account. Admin browser authentication was refreshed using its
existing private credentials and MFA; no new enrollment or grant was created.

Sample records and screenshots must not include credentials or real personal
documents. Record safe counts and fictional labels in Git; private account IDs,
operator sessions and operation manifests stay outside Git.
