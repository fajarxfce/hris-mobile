# Recipient destination observation

A recipient route can observe its retained canonical destination without initiating discovery, conversation setup, SDK acquisition or delivery. ObserveCollaborationSpaceRecipientDestination coordinates the existing catalog and immutable ConversationMetadata repositories under current account/Group admission. No second association store, repository-to-repository call or use-case-to-use-case call is introduced. Actual application DI resolves the observer.

Each admitted content collector owns at most one metadata pair and its catalog version. A known pair survives unrelated insertions in the same generation with a nondecreasing revision, so those publications do not repeat findByPeer. Missing peers are checked once per changed catalog publication. The first exact lookup requires an equal subsequent catalog witness before publication/caching; a raced lookup is skipped and the existing owned source stream supplies its next frame. There is no retry loop or timer.

Null/removal, generation replacement, revision regression, malformed input, storage failure, denial and cancellation invalidate the association. Identity/admission source completion redacts and joins the content reader. Ordinary credential age does not erase account-owned local facts. Confirmed denial stays latched through failed retries until a newly observed admission; final ownership checks suppress late results.

Sixteen common domain cases cover local-only reopening, absent catalog and peer creation, thirty unrelated insertions without reacquisition, coherent lookup races, cache invalidation, finite failure behavior, malformed coordinates, denial/readmission, account replacement, source completion and disposal. They run on desktop and browser; generated application DI adds two existing context tests. Apple compilation is source evidence only, not a new device runtime result.

This observer is a prerequisite for composing a recipient thread with canonical commands/history. No product route is activated by this packet. Durable pending acknowledgement requires exact original provenance, and independent recipient/canonical drafts still require explicit editor/index continuity. PrepareCollaborationSpaceConversation also needs finite application ownership after an explicit retained Send; passive observation must never initiate that work.

Phone release 93 remains installed and predates this component. Group is OFF, migrations V117–140 remain undeployed and all 54 PRD acceptance groups remain OPEN. This policy belongs to HRIS Mobile, not Starter. Preserve the blocked SQLite fixture preparation and failed hosted Apple run as separate evidence.

Validation: 18,249 cases (16,060 fresh / 2,189 exact unchanged), preserving all 18,217 prior identities plus 32 executions. Focused 18 and full root/Android/browser/Apple-source gates passed. Source and report manifests are recorded below.

Earlier focused attempts are retained separately in the measurement record; only the final accepted source/gates establish this result.

[Exact validation](measurements/collaboration-recipient-destination-2026-11-14.json).
