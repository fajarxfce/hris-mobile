# Accessibility and adaptive layout

The design system keeps rendering mechanics in shared `AppXxx` components. Feature
pages provide immutable state and actions; navigation and ViewModels retain their
existing ownership.

`AppNavigationScaffold` uses the available window width:

| Width | Navigation |
|---|---|
| Below 600 dp | Bottom navigation |
| 600–839 dp | Side rail |
| 840 dp and above | Expanded sidebar |

The content uses movable composition, preserving its rendering state across width
changes. Navigation items have stable keys, tab/selection semantics, keyboard focus,
and at least a 48 dp target height. The bottom bar owns the bottom system inset on
small screens; pages own it beside a rail/sidebar. The selected item is indicated
by its icon/text color and a sidebar marker without a selected card background.

Page content scrolls and does not impose a fixed text height. Summary cards wrap
when there is insufficient width. `AppHeading` identifies screen headings;
`AppTextField` exposes its label and validation error to accessibility services.
Icon actions have descriptions, loading buttons expose progress semantics, and
feedback uses a polite live region. Text selection follows the theme accent.

Desktop Compose UI tests cover responsive navigation, preserved content lifetime
on resize, selected semantics, text at 200%, input labels/errors, minimum button
height and Enter-key activation. Screenshots are written to the test reports.
These checks do not replace TalkBack/VoiceOver tests on physical devices. Review
focus order, announcements, contrast, switch access, RTL requirements, and each new
custom Fluent component on the intended platform. The community Fluent library
remains experimental; do not assume every SDK component has equivalent platform
accessibility behavior.
