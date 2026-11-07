# Newly inserted chat messages at the end of the thread

A reverse LazyColumn preserves its visible item's stable key when a new row is inserted.
At the end of the chat that kept the former newest row anchored and left the new pending
row below the visible area until the later send-confirmation scroll. Actual release54/55
recordings show the initial clipped bubble; two controlled UI cases reproduce the old
first-layout failure at320×640 with2× text and at720×375.

The existing route viewport composition now owns both end anchoring and visibility,
and is named ChatThreadViewport. It remembers the last presented newest key. On a
changed key, SideEffect requests index0 before the next measurement only if the user
was exactly at index0/offset0, is not scrolling or browsing history, has no newer page,
and is not leaving, forwarding or denied local content. A reader higher in the thread
keeps stable-key anchoring. This also works during STARTED entry before resumed read
tracking. Visibility observation retains its existing lifecycle/focus/disposal behavior.

ChatThreadPage is unchanged. ViewModels, delivery, durable message IDs and read
admission policy are unchanged. This does not fix the separate composer-disable/
keyboard issue or establish phone transport latency.

The original two failed frame checks are retained. An intermediate page-level effect
passed UI tests but failed architecture; it was moved into the existing route owner.
The next UI fixture initially lacked LocalLifecycleOwner; this is recorded rather than
hidden. Final focused79843 passes13 actual UI cases in61.538s. Full21721 passes927 fresh
cases across the recorded suites in387.643s on4,415 unchanged inputs, zero failures,
errors or skips. Root checks, Android host/release lint, quality and shared Wasm
compilation pass; Android lint has zero issues. No new browser UI runtime or phone APK
is claimed. [Exact source/checks](measurements/chat-new-message-position-2026-11-07.json).

The actual controlled first-frame captures were opened and inspected:
[narrow large text](images/chat-new-message-position/chat-new-pending-first-frame-portrait.png)
and[landscape](images/chat-new-message-position/chat-new-pending-first-frame-landscape.png).
They are rendered layout evidence, not pixel-baseline comparisons. The later device
probe must preserve original data and distinguish this fix from keyboard dismissal.
