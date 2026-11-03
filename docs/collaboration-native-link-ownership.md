# Ownership for native chat navigation

A notification can refer to a different company while the user is selecting another
company or changing accounts. A delayed lookup must not overwrite that newer choice.
This increment provides the guarded selection and observed identity needed by the
application's typed notification route. [Evidence](measurements/chat-native-link-ownership-2026-11-03.json)
records the six source files and actual checks; native routing is the next increment.

`WorkspaceSelectionOwner` captures the local session, API audience, account and
native session family. `WorkspaceSelectionOrigin` also captures the selected
workspace ID; null explicitly means there was no selection. Their string forms
redact identifiers. Ordinary two-argument `SelectWorkspace` callers remain compatible.

When an origin is supplied, selection rejects a different owner before company or
account I/O. It then checks the original workspace ID before requesting membership,
permissions or company policy. Continuations check the captured session, user and
binding before using successful lookup results and before committing. The existing
compare-and-set protects a choice made while lookup is in progress. A final check
withholds success after cancellation or identity replacement during commit.

An uncertain late commit remains a fact belonging to its original owner. It is not
compensated by clearing whichever workspace is now selected. Navigation must require
both a successful result and the exact resulting workspace in the current context.
Native opening creates neither message commands nor read receipts.

`WorkspaceContext` now exposes the actual selection owner, including when no company
is admitted. Existing local ownership policy still excludes a selection issued by a
different native family; stored data remains untouched. Application navigation must
use this actual owner instead of treating a local session ID as the whole identity.

Six new tests cover all four identity mismatches before I/O, token renewal, account
and family replacement at each lookup, late commit and cancellation, a manual choice
made before lookup starts, and actual observed identity after family replacement.
The initial rejected test expected the previous-family workspace to remain admitted;
the test was corrected to preserve the existing policy. No production guard was relaxed.

All 34 workspace domain tests pass. Full root, Wasm compilation, Android dev assembly
and lint pass in 838.365 seconds with 3,720 fresh cases in 476 suites and zero failures,
errors or skips. Complete counts and report reuse are in the evidence. This does not
claim product native navigation, notification-host activation or phone delivery.
Full hosted Apple will include this source after the current callback run finishes.
