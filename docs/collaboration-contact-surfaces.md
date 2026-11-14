# Retained contact browsing and search

The Group contact model keeps an unfiltered directory and an independent search projection from
one admitted local snapshot. Editing a search leaves the browse rows untouched. Returning to the
contacts tab retains its viewport; reopening search retains its query and results. Neither screen
starts HTTP or Matrix work. A new local directory publication updates both projections.

SpaceContactsContent binds both entries to the same admitted SpaceChatRoot and model, while each
entry owns its list position and resumed lifecycle. Events and effects carry their originating
surface. A late pause from an outgoing entry cannot deactivate its replacement, and an obsolete
click cannot open a contact through a retired Group root. Selection remains possible in the browse
list while a different search query is being computed.

The public inbox and thread-route entries now take the same Group handle instead of separately
supplied graph and store arguments. This prevents callers from mixing account/Group resources.
Five shared state cases and two actual desktop navigation cases cover retained browse/search,
query recomputation, publication updates, root denial/readmission and stale callbacks. The existing
three contact images remain byte-identical to the separately inspected accepted light, dark200%
and landscape images. Navigation tests observe retained search rows on the first rendered entry
frames; these are desktop fixture results, not Android latency measurements.

The app's product routing remains inactive. Next give concurrently retained Group and Company
navigation stacks independent window-owned identities, without moving AppViewModel into a route.
The reviewed unapplied draft is in .work/navigation-stack-identity-2026-11-14. New-contact entry
still needs durable peer-addressed draft storage and a handoff to the canonical conversation;
peer IDs and original setup-command IDs must never masquerade as conversation IDs. Complete public
company memberships are still needed before filtering contacts by company. Eight display labels
plus hasMoreCompanies cannot establish non-membership.

No new APK, phone interaction, live deployment or Apple runtime execution is claimed. Phone92,
GroupOFF and undeployed V117–140 remain. All54 communications PRD acceptance groups remain OPEN.

[Exact validation](measurements/collaboration-contact-surfaces-2026-11-14.json).
