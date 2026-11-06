# Chat first-frame rendering — 6 November 2026

Repeated entry now renders saved bubbles during the Android navigation transition,
both offline and online. The first room opening after process restart still showed
about233ms of blank body after the header and remains an open performance issue.
Phone build40 is unchanged. [Measured candidate and validation](measurements/collaboration-chat-first-frame-retention-2026-11-06.json).

## Guarded first-opening read scheduling

Navigation now passes the already captured account/company scope as an optional
Entered event hint. The ViewModel starts private thread observation from this scope
while the ordinary workspace observer initializes. The domain reader still checks
current session before private I/O, and fresh workspace/admission before every
publication. Identical observed ownership does not restart history. No-hint entry
retains the existing path. No credential or plaintext cache is added.

Four new regressions cover delayed workspace observation, equal owner without a
second history read, subsequent session loss, foreign account, missing current
workspace and retained denial. Focused thread-hint1 passed228/131.455s on4,202
unchanged inputs, including app desktop compile and static/architecture checks.
Candidate5 release/lint passed113.005s; uninstrumented signed arm64 APK41
SHA21938aa5443b3eea2d614bee76555946cf58b96e01afd8319434d115492ed5c4.

Six passive recordings use three fresh app processes: two offline startups and
one online, each followed by a repeated room entry. All retain nine messages.
First-entry header-to-bubble gaps are200/133/133ms; repeated entry is0/0/−33ms.
All12 adjacent frames were inspected: first entries still render an empty room
before bubbles, whereas repeated entries render bubbles during the transition.
The final online repeat also shows a contextual reconnect prompt. Compare that
cold-online session behavior against the prior candidate before release; do not
infer that read scheduling caused it. All settings restored, owned emulator stopped
with data/keys retained, no sends or phone action. Root/browser/Android validation
passed after correcting the separate native-proof app to call Entered(). The first
full attempt passed2,288 fresh tests but failed that proof compilation; the final
attempt passed in95.034s with tests up-to-date. No fresh tests are claimed for that
final attempt. Candidate5 predates only the proof-app constructor-call correction.
[Candidate5 evidence](measurements/collaboration-chat-first-opening-hint-2026-11-06.json).

## Current behavior and ownership

The account/company navigation graph retains up to three idle room ViewModels.
Each real back-stack entry owns an independent bridge ViewModelStore, including
simultaneous entries for the same conversation. Popping an established latest-window
room releases foreground/native work, retires selection, dialogs, editor state and
queued route effects, and keeps its scoped local observations. Reentry reads the
existing StateFlow value without constructing the history again. Normal drafts and
messages remain durable. Pending context and historical windows are disposed.
Replacing the session/company graph clears both idle and active stores.

The domain thread reader combines local context, history and commands, then checks
current owner/admission before publishing each bounded immutable snapshot. Initial
composer/reply reads follow the first history snapshot. Empty edit-outcome queries
perform validation without storage I/O. These changes neither acquire API data nor
cache credentials. Data-layer permission/module regexes compile once. Bubble width
uses a measure modifier instead of a per-row BoxWithConstraints subcomposition;
the page computes its timezone once.

The navigation cache owns screen lifetime. The ViewModel handles typed events and
state; the use case coordinates repositories and pure policy; existing repositories
continue to own datasource composition. Three idle stores bound retained state and
subscriptions. There is no process-global room cache or retained Activity.

## Android evidence

Candidate4: signed nondebuggable arm64 devRelease41, assembly/lint270.160s;
SHA569653c31d21cb2d5054b6f7d6ba91a43a799f496eabb8b9c45a2189519f0645.
Original signer, private API, Firebase app,16KiB alignment and compiled backup
exclusions verified; diagnostic markers absent. Installed only on the owned Bima
emulator, preserving application identity, data and crypto keys.

The app process was explicitly restarted before four passive recordings. No UI
hierarchy inspection ran during the eight-second videos. All four ended with nine
visible messages; no new messages were sent. Settings were restored and the exact
owned emulator stopped with data retained. All eight adjacent before/detection
frames were inspected. Repeated entries show bubbles while the hub and room still
overlap during transition; the first entry shows a fully rendered blank room before
its messages appear.

| Opening in fresh process | Network | Header-to-bubble threshold gap | After full header |
|---|---|---:|---:|
| First | Offline |233ms |233ms |
| Second | Offline |−33ms |−133ms |
| Third | Online |−33ms |−33ms |
| Fourth | Online |0ms |−33ms |

Measurements use fixed video regions resampled at30fps, about33ms granularity.
Negative values mean the bubble-ink threshold was crossed before the header
threshold. Very faint bubbles can already exist in the preceding frame. These
numbers are neither tap latency nor physical-phone benchmarks. The visual evidence,
rather than a gap shorter than an arbitrary animation duration, supports the
repeated-entry conclusion. First-entry performance is explicitly not accepted.

## Validation

Retained-room3 passed163 fresh tests in67.716s with4,202 unchanged inputs; other
requested checks remained up-to-date. A real NavHost test separately passed and
shows retained content at32ms during a180ms transition, with old graph disposal.
Actual room tests cover immediate rows, no reopened history subscription, retirement
of old copy effects, normal future effects, hidden session loss and pending-context
disposal. Store tests cover active duplicate rooms, idle eviction and graph cleanup.
The first full root/browser/Android gate passed6,938 fresh tests in804.331s.
Review then found a pending departure could outlive the popped route while an
independent draft write finished. A regression failed on that behavior. RouteReleased
now cancels/clears the old departure and leaving/dialog state while the draft writer
can finish. The same scenario then passed with197 focused cases in98.875s.
Final root/browser/Android-debug/lint gate passed2,280 fresh tests in368.759s on
4,202 unchanged inputs; other requested checks remained up-to-date. Both Android
lint reports contain zero issues. The recorded release candidate predates only this
lifetime correction and its test; it is not a final release or phone delivery.

Earlier test fixture failures are preserved: private serializer visibility, a
cleanup loop hiding the original graph lookup error, then the fixture was aligned
with production key(scope) and parent lookup remembered per entry. The resulting
gate passed without relaxing expectations. Bubble screenshots for light/dark,
large text, landscape, selection and quotes were inspected in the prior focused
gate. The two generic MVI files are applied in Starter after this Mobile consumer proof;
its independent local and Apple validation passed. HRIS room policy and UI stay in Mobile.

## Investigation history and remaining work

[Release40 baseline](measurements/collaboration-chat-first-frame-baseline-2026-11-06.json)
showed367–533ms after the header, including offline. Its last trial acquired a ninth
message; a later paired comparison used nine throughout. The first local-read
candidate reduced that paired gap from333–467ms to233–300ms, but still showed a
blank body after the full header. Candidate2 measured200–267ms after the full
header. Subsequent passive candidate2/3 measurements also retained a gap and did
not establish a reliable benefit from removing one duplicated workspace read.

Valid ART samples identified repeated workspace regex construction and protected
local reads. A temporary emulator-only diagnostic APK measured18/24/23ms from
projection to first draw, with247/260/273ms from entry to draw overall. Those stage
samples diagnose the read path, not unmodified release performance. Instrumentation
was removed and original source hashes reverified before later changes. Do not
ship or reapply the diagnostic APK/scripts. Private videos/traces stay in.work;
Git evidence contains no message contents.

Continue first-opening performance, native encrypted edit/raw-history proof and the
complete communications PRD. The native HRIS CI fixture pin has separately moved
from Platform304be to already accepted10485916; its prior generic RuntimeError is
not an exact root cause and a new successful native run remains required.
[CI pin evidence](measurements/collaboration-native-ci-pin-2026-11-06.json).
