# Organization chat presentation integration

Status: integration planning, 13 November 2026. Canonical unresolved/bound thread,
indexed contacts and coherent local participants are accepted, followed by immutable
submission and encrypted Group editor/outbox actions. Bounded recovery and independent local editor/outbox observation are accepted. Group route/list UI activation and measured rendering
remain pending; no additional PRD acceptance group is closed by these prerequisites.

## Product interaction

Keep the existing Fluent visual language and the primary Chat bottom destination.
The hub keeps its swipeable Conversations/Contacts categories, search with an adjacent
filter icon and a scrolling FAB. Call history joins this hub when its actual data and
call actions are implemented. A room opens above the bottom bar with Back and its peer
name. Message actions begin with long press, then selection tap/multi-selection. Native
accessibility actions must expose the same choices. Connectivity diagnostics and a
permanent selection button do not belong in this header.

Keep the newest outgoing row adjacent to the composer, with stable identity across
provisional, queued and confirmed states. Day dividers and compact delivery metadata
retain the current time/Today/Yesterday/weekday/date rules. Never infer delivered/read
from a send response or timer. The send action accepts the next local message while
previous commands are pending. Actual persistence failures keep the exact original
submission available for recovery.

## Existing component review

ChatThreadPage already applies its caller modifier to both its forwarding branch and
normal root. Its bounded reverse LazyColumn, composer inset handling and stable row
keys should be retained. The current room header and selection bar are separate
semantic states. The page receives state/events; navigation still owns route lifetime.

ChatMessageBubble already has a root modifier and nullable quoteContent slot. selected,
selecting and enabled are semantic interaction states, so they remain constrained
parameters. Time, delivery and forwarded/edit markers also remain semantic values.
No new slot or generic wrapper is justified for the current text-only rendering.
Introduce media/body regions only alongside implemented rich-message variants.

ChatComposer currently receives Company ChatThreadState/events. Reuse its invariant
multiline field, optional reply/edit header and semantic send/save action when the
organization state is defined. Keep focus and keyboard mechanics in composition;
durable submission, retry and cancellation policy belong in domain. Avoid a matrix of
Company/Space flags in this widget. A shared visual API needs concrete typed callers
before extracting it, rather than anticipating arbitrary options.

## Data and ownership decisions to resolve

The accepted canonical snapshot retains separate Space and Original command ledgers.
Presentation must preserve those namespaces in row/action identities; equal UUIDs
across them cannot select or delete one another. The original Company coordinate is
physical provenance, while Group admission controls this organization thread.

Recipient labels read the accepted exact published contact index.
Retained registration.command.provider contains selected sender metadata and is a
possible local source after bounded ownership/identity validation. Room origin and
provider identity origin are independent: neither selected HR company nor room origin
may be used to invent a Matrix user ID. Rendering history before identifying its own
sender can cause first-frame bubble-side changes. Review the existing three visible
thread stores and measure cold/warm entry before choosing publication timing.

The existing visible-thread preparation is Company navigation-owned. Organization
routes need the equivalent account/Group owner so switching HR company keeps their
owned prepared state, while account replacement/revocation clears it immediately.
Continue to bound idle rooms to three and release live work on route stop; animation
must not initiate data acquisition.

QueueCollaborationSpaceText now accepts the caller's complete immutable intent. Group
editing/submission actions and typed encrypted storage preserve the original ID, text,
quote and captured timestamp across interrupted writes and renewed login. Bounded
recovery separates retained submissions from pending native messages; unavailable keys keep the wake with scheduler backoff.

The next read boundary observes the Group editor and retained outbox under local
account/Group admission, independently of history so editing I/O does not gate message
rendering. Its seven source paths and18 read/lifetime methods are accepted locally;
18 actual hosted Apple executions remain required. Known read failures publish without waiting for an unrelated source;
first success includes actual editor/outbox results. Held originals remain explicit.

A concrete Group route owns a typed Group ViewModel and the existing serial composer
actor's lifetime. Capture complete draft edits and send gestures before suspension. The
actor coordinates UI writes and fences late callbacks; use cases own admission, exact
replay and recovery. Shared visual components receive explicit rendering state and
callbacks from their Company and Group callers. Group action identities retain their
command namespace and canonical ownership throughout selection and contextual actions.

## Skill review and validation

Read ui-ux-pro-max and compose-component-design, including modifier-layout and slot-apis.
The verified Jetpack Compose search matched stable Lazy keys, virtualized LazyColumn
and correctly keyed LaunchedEffect. Apply those recommendations to the existing Compose
stack. Two UX searches did not produce a specific long-press action pattern; no result
from those searches is persisted as verified guidance. Long-press behavior here follows
the user's explicit requirement; accessible alternatives follow the existing component
semantics and general accessibility guidance.

Before implementation acceptance, exercise retained/offline first frame, fast sequential
sends, scroll anchoring while IME opens, back/tab interruption, large text, light/dark,
long-press/multi-selection and account/Group revocation. Inspect rendered screenshots
and record frame/latency measurements on the dedicated emulator. Screenshot evidence
and a successful build are separate from actual delivery/call behavior. Phone delivery
remains install-only after its own accepted release checks.

## Accepted local participants

The canonical thread now publishes history with actual local participant results on its
first success. Observed absence and failed metadata remain explicit; no provisional
identity is fabricated. This preserves coherent initial publication. Bounded route warming
and cold/warm first-frame measurements are still required before claiming immediate room
rendering. See [participant evidence](collaboration-space-thread-participants.md).

## Accepted per-message progress and pending presentation

The per-original observer now reads authenticated active-or-terminal Group records under
local account/admission ownership. Its20 shared methods passed locally; actual hosted Apple
execution remains pending. Outbox absence preserves originals, and unchanged record readers
survive request-list changes. See [progress evidence](collaboration-space-composer-progress.md).

Thirteen accepted presentation source paths now include independent history/editor/progress collectors,
exact retained-original continuity and recent terminal visual identities. The implementation uses
real Group ownership and preserves Company command namespaces. Its 32 new shared methods and
11 existing actor cases pass locally, including 279 presentation browser cases. Actual Apple
execution of the 32 new methods remains pending.
Actual Group route/list/page activation and rendering measurements follow that acceptance.

## Accepted typed timeline rows

The Group ViewModel now publishes typed rows with its composer continuity. Group and
Original command namespaces remain distinct; exact provider/origin evidence joins history.
Route-local placement stays stable across confirmation without changing real timestamps.
21 new shared methods pass on JVM/browser; actual Apple execution remains pending. Concrete
page/route activation, contextual actions and rendering/IME measurements are next.

## Accepted Group page and explicit actions

The actual page/content now reuses bubble/day-divider/composer visuals with typed selection,
copy and reply. Local action admission is a domain use case; the route owns clipboard, focus
and first-layout anchoring. 100 focused cases and three reviewed desktop screenshots support
this increment. Product graph/list activation, bounded warming, remaining mutation actions
and actual Android IME/transition measurements still follow.
