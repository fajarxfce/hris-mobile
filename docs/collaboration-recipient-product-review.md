# Product integration after recipient owner acceptance

The existing encrypted ConversationMetadata repository already retains the immutable peer-to-canonical mapping. Reuse it; do not add a second association store. The unresolved work is its admitted observation, command/history composition, independent editor/index continuity and finite new-recipient setup after Send. See the updated recipient projection review. Earlier references below to defining an association are superseded by this trace.

The [typed recipient editor and MVI consumer](collaboration-recipient-editor.md) are now implemented and tested. The durable pending/history projection, canonical association, represented-original acknowledgement and active product routes below remain required.

Read-only trace, not an implemented route or schema proposal.

SpaceChatInboxViewModel owns a retained Paging3 Pager (50/page, 150 loaded), a version publication and bounded visible name subscriptions. SpaceChatInboxPage currently renders only CollaborationSpaceInboxEntry, whose identity is a canonical conversationId. Existing pending activity/drafts are already ranked in that canonical index. SpaceChatThreadRoute likewise accepts only conversationId. SpaceContactsContent emits accountId under the retained account/Group root. A peer account ID must never be passed to the canonical thread route as an alias.

Recipient drafts/originals are in their own account/Group replica partition. The accepted submit operation preserves a captured peer-addressed gesture before canonical setup; transfer pins and verifies the later canonical destination. The new local owner is the recovery prerequisite, not a complete pending list or navigation flow.

Next define the recipient product identity and retained route/composer ownership. A new-contact page must render local drafts immediately, capture every explicit Send independently of later typing, use typed MVI events, and survive cancellation/uncertain acknowledgement without replacing the original. Route lifetime belongs to the existing Group root, outside Company scope. Contacts label/admission and canonical destination metadata remain separate facts.

For pending list integration, preserve global ordering, source timestamps and stable row keys through canonical handoff. Do not append a separate temporary header/list or disguise peers as conversation IDs. Avoid materializing all 5,000 employees or draft peers to merge pages. Trace the existing bounded replica queries/multi-replica mutation contract and index codec before choosing the durable recipient projection. Test no duplicate/missing row around destination seeding/source retirement, rapid sends, independent later typing, process restart, changed paging anchors and account replacement.

A coherent product update must include actual root/contact/thread navigation, rendered interaction tests and screenshots using the existing Compose/UI skills, plus browser behavior and Apple source compilation. Group rollout/live V117–140 remain separate and OFF until their own gates. Physical phone remains install-only, with a new version above 93 after validated product changes.

## Bounded index option to evaluate

The raw store already has coherent multi-query pages and multi-replica mutations. One candidate is a distinct recipient-row document/tag in the existing inbox partition, sharing its durable lastOrdinal. A new typed combined window could read at most limit+1 canonical rows and limit+1 recipient rows under one checkpoint and merge the bounded pages; old canonical-only queries keep their tag and format. This avoids re-tagging every legacy canonical row solely to make it discoverable. It still requires exact cursor/anchor semantics and atomic recipient source/index publication coordinated by use cases, not repository-to-repository calls.

Do not implement that option without resolving canonical handoff identity: independent later recipient typing can remain after an original transfers. Simply removing the recipient row would lose its draft; retaining both rows would duplicate one DM. A persisted, authenticated canonical association and a clear composer/draft migration policy are required, including legacy canonical conversations without recipient provenance. Visible-label lookups or UI-only deduplication cannot establish that durable association. These are unresolved design decisions, not an accepted storage migration.

Existing ChatComposerEditingSession is access-generic but its draft/submission types and conversationId are canonical. Reusing it by passing peerAccountId as conversationId would violate the current identity boundary. Trace its serial captured-write/rapid-send behavior before defining a recipient editor owner or a narrowly justified typed refactor; do not move persistence policy into the ViewModel.
