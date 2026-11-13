# Owned local conversation catalog admission

ReadCollaborationSpaceConversationCatalog admits one finite metadata page under the current
account, server and Group. It checks local identity and directory admission before and after
storage, preserves explicit Restart and cursor-bearing empty pages, verifies canonical pair
ownership and returns immutable items. Expired credentials and the selected HR company do
not gate owned local data. It does not acquire a native client or make an API request.

ObserveCollaborationSpaceConversationCatalog owns one version subscription per collector.
Equivalent token refreshes do not reopen it. Session replacement cancels and joins obsolete
reads; final identity and admission checks prevent late values escaping. A confirmed denial
remains closed through failed admission and reopens only after an admitted local gate. Known
denial returns immediately without waiting on an unrelated session read. Equal published
versions are suppressed; a completed failing source does not start an automatic retry loop.

Seventeen domain methods cover finite reads, invalid ownership, cancellation, late results,
denial, slow consumers and expired sessions on JVM and browser. The focused gate has45 cases,
including26 existing catalog/metadata storage cases and two actual app DI cases. Full checks
retain all15,541 prior identities plus34 new executions, totaling15,575. Apple test compilation
passes; the17 actual Apple executions remain for the combined hosted control.

The catalog is a bounded discovery input. Visible recent-chat order still requires the
persistent activity index described in collaboration-space-inbox-index-plan.md, including
pending and draft previews. No product Group graph, native runtime or live feature is enabled.

Evidence: `docs/measurements/collaboration-space-catalog-admission-2026-11-13.json`.
