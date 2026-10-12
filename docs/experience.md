# Mobile experience

Use Fluent visual language with a mobile-first layout. Keep navigation, wording,
and actions direct. Company identity and the current employee scope must remain
clear without turning the phone screen into a compressed administrative portal.

- Use compact headers, readable lists, clear grouping, generous touch targets,
  and a small set of stable bottom-navigation destinations. Selected items use
  color and emphasis rather than a separate card.
- Preserve each tab's position, keep keyboard and system insets correct, and
  prevent a response from moving controls while the user is interacting.
- Use brief page transitions, selection changes, and inline feedback. Motion
  should explain a change of context or state, usually within 120–180 ms. Avoid
  decorative loops, large bounces, or staggered animation on every list row.
- Honor reduced motion and system animation settings. Completion, errors, and
  pending synchronization must remain understandable without animation.
- Replace account/company content immediately when its authorization scope changes;
  do not retain private outgoing content for a transition.
- Keep existing content during refresh. Reserve initial loading placeholders
  for missing content, and show an explicit recoverable error if loading fails.
  `AppLoading(compact = true)` keeps a labelled progress ring inline with existing
  form or page content; its default remains the initial-loading layout.
- A queued offline action is visibly pending, not confirmed. Retain the command
  and its outcome in state/storage independently of a transient success effect.
- Lifecycle-aware route collectors own effects. Animations, timers, and input
  listeners must stop with their owners; no network or database work belongs in
  composition or animation callbacks.

Check light/dark contrast, text scaling, screen-reader labels, focus, keyboard,
touch targets, tab state, and interrupted transitions. Build/test results do not
establish frame-time or memory performance; record actual device observations
and measured workloads separately.

`AppListRow` provides one accessible action, bounded title/summary text, optional
metadata, and clipped press feedback. Place dates below the summary when a
trailing column would crowd a narrow screen. `AppLazyPage` accepts an owned list
state so paging can reset its position without rebuilding the route owner.

Home shows a compact welcome card with the employee's name and company. Company
switching belongs in Account. Today's clock-in and clock-out appear before the
service grid. All services opens a separate page of admitted destinations grouped
by purpose. The four stable tabs are Home, Inbox, Employees and Account. Each
feature owns its state and actions; Home receives its entries through app
composition and does not acquire another feature's data or ViewModel. Long bottom
navigation labels use a single ellipsized line while retaining their complete
accessible names.
Navigation items accept a composable badge slot. `AppCountBadge` displays counts
above 99 as `99+`, retaining the full localized accessible description. Zero and
unknown counts have no badge. Feature entry points own count observation; the
navigation components own only its layout in bottom bars, rails and sidebars.
The service grid does not repeat Inbox, Employees or Account destinations. Personal
profile access belongs in Account; the grid is reserved for work services.

`AppTopBar` belongs in the `topBar` slot of `AppPage` or `AppLazyPage`, outside the
scrolling content and its padding. Its normal minimum height is 56 dp; large text
can increase it. Inner pages use 16 dp horizontal content margins and 48 dp icon
targets for back and refresh. The app host still owns top/side system insets, and
the page or bottom navigation owns the bottom inset.

Use `AppActionBar` in `AppLazyPage.bottomBar` for a page's primary action, keeping
it visible without covering the final list row. `AppIconButton.loading` retains
its target and accessible label while preventing duplicate input. Progress rings
belong beside the operation they describe; avoid moving progress below a long list.

`AppMenuGrid` lays out up to four columns and reduces that count as text grows.
`AppMenuItem` provides one named action per icon and label. Use text actions for
secondary navigation, radio rows for mutually exclusive preferences, and switches
for boolean preferences. Settings keeps a persistent back action above its
language and security groups. These components add no data observation or
navigation ownership.

Calendar time ranges carry more visual emphasis than source metadata. The saved
timestamp remains available after the agenda, while refresh failures remain
visible above the retained data. Language, appearance and text-size changes
must retain the current workspace and navigation state.

Home retains the clock fields and disabled actions during initial loading.
This keeps the basic card layout consistent during acquisition.
Home shows the work date, shift and clock actions. Attendance opens monthly
metrics and daily history with a fixed month filter and read-only day details.
Absent totals require confirmed records. History has no clock-in/out controls.
Location is requested on a Home capture action, with an explicit fallback when
it is unavailable.
Saving offline, receiving a server receipt and accepting attendance are separate
visible outcomes. An uncertain response retains the original command for retry.
The Home card owns capture and pending-command dialogs separately from monthly
history. Leaving its tab or stopping the app cancels acquisition and user actions.
Its navigation ViewModel retains data and observes cache validity until that owner
is cleared. Returning to admitted loaded content does not refresh it; an unfinished
request or invalidated read needs acquisition. Returning never records attendance.

## Company selection

Company rows provide one selection target with the company name, code and a
labelled progress indicator. Other actions remain disabled until selection fails
or the application opens the admitted company. Sign out stays in the fixed bottom
bar. Account shows company-switch progress in its header and sign-out progress
beside that action; navigation controls cannot interrupt either operation.

Leave keeps the request action below its balance/history list. Request details
separate status and dates from the reason and activity history; available actions
stay in the fixed bottom bar. Refresh temporarily disables those actions without
moving them. Delivery recovery remains attached to the original saved command.
Creation keeps the confirmed calculation in the scrollable review and submission
in the bottom bar, including at large text sizes.

## Search and filters

`AppTabs` renders controlled content categories with an underline and no separate
selected container. Labels wrap at large text sizes, each target remains at least
48 dp, and disabled tabs cannot dispatch selection. Features own selection and
the corresponding data request.

Use `AppSearchEntry` to open a dedicated search page from a browsing list. It is
one accessible action and does not create an editor or open the keyboard itself.
The search page keeps Back and `AppSearchField` above the scrolling results.
Initial focus belongs to the field's composition; query changes do not steal
focus after the user dismisses it. Keep the accessible label and explicit clear
action. `AppTextField` remains for labelled forms. `AppFilterChip` opens an
owned filter surface; `AppDialog` delegates focus, dismissal and window lifetime
to the platform. Feature MVI owns visibility, selection and pending data work.
Department search and coworker search use independent cancellable requests;
leaving their owner releases both. Directory filtering occurs before server paging.
Search owns a separate navigation entry, query, filter and request lifetime.
Returning preserves the browsing list's department, page and scroll without
refreshing an already admitted result. A failed search offers explicit retry.

`AppMonthPicker` receives localized labels, selection and enabled choices from its
owner. It adapts its column count to text size and does not acquire data or decide
which periods an application permits. Month selection resets the history list
position; refreshes of the same month retain it.

`AppDatePicker` renders a controlled seven-column month grid. The caller supplies
short localized weekday labels, full-date accessible descriptions, selection and
availability. Calendar navigation and date policy remain with the feature. Put it
inside a scrollable dialog so large text does not displace the fixed close action.
