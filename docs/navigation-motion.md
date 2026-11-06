# Directional page navigation

The application navigation host uses a 280 ms horizontal page transition. Pushing
an inner page brings it from the trailing edge while its parent shifts back by
one third of the viewport. Back reverses those movements. RTL mirrors their
physical direction. Pages retain their opaque surfaces and movement is clipped
to the navigation viewport, so outgoing and incoming text are not cross-faded.

The host and design-system motion token own this presentation behavior. ViewModel,
use-case, repository and data acquisition responsibilities are unchanged. Prepared
chat content stays available when navigation begins. Account/company graph
replacement still disposes the previous scope immediately. Reduced motion uses
no page transition; tab/selection timing keeps its shorter existing values.

The new actual-NavHost regression failed with the previous fade-only behavior.
The corrected focused run passes five cases, including forward/Back geometry in
LTR and RTL, content present during movement, reduced motion, route ViewModel
restoration and account disposal. Full verification passed 6,979 fresh tests. The
first full run failed only in debug APK packaging; the unchanged-source repeat
passed the complete command, with tests up-to-date and zero lint issues.
[Measurements](measurements/navigation-motion-2026-11-06.json). [Release42 Android acceptance](measurements/collaboration-release42-2026-11-06.json)
includes four first/repeat offline/online entries and four Back recordings. All
eight strips were visually inspected: bubbles remain present during motion and
the retained list returns without a blank page. Release42 is installed on the
authorized phone with preserved data and no phone UI interaction. These recordings
establish the observed movement and content, not frame-time performance.
