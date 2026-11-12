# Organization chat presentation integration

Status: implementation planning, 12 November 2026. Canonical unresolved/bound thread
observation is accepted at a74526b7. Indexed local contact observation is being tested.
This plan does not claim that the organization UI is already connected or that any
additional PRD group is accepted.

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

Recipient labels should read the exact published contact index currently under test.
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

QueueCollaborationSpaceText currently creates createdAt after its guarded local reads
and exposes an ID/text invocation. Organization composer work must preserve the same
immutable submission ID, original text, reply target and submission time from optimistic
presentation through persistence/retry. It also needs owned draft/process-loss recovery.
Do not relabel queued timestamps or reuse Company draft storage under a fabricated
company identifier. Review the existing Company durable composer contract before
choosing the explicit Group storage/workflow changes.

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
