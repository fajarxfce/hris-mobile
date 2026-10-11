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

Home separates the active company from employee actions. Attendance, calendar and
inbox are available as quick actions; Services groups all admitted destinations by
purpose. The three stable tabs are Home, Services and Account. Account preferences
and session controls stay grouped in Account. Attendance, calendar and inbox pages reuse
`AppScreenHeader`, with compact titles and consistent back/refresh targets.
`AppActionTile` provides a single accessible action with supporting text. These
components add no data observation or navigation ownership. Each page chooses its
content padding without changing the shared inset or scroll-state ownership.

Calendar time ranges carry more visual emphasis than source metadata. The saved
timestamp remains available after the agenda, while refresh failures remain
visible above the retained data. Language, appearance and text-size changes
must retain the current workspace and navigation state.

Attendance puts today's work date, shift and clock actions before history. Location
is requested on a capture action, with an explicit fallback when it is unavailable.
Saving offline, receiving a server receipt and accepting attendance are separate
visible outcomes. An uncertain response retains the original command for retry.
