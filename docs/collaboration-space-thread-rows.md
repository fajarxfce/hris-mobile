# Group timeline rows and stable outgoing placement

Group thread state now supplies typed display rows from its independently observed history,
commands and local composer. Each identity includes canonical account/Group/conversation
ownership and its real Group command, Original Company command or provider-event namespace.
Equal UUIDs in different namespaces remain distinct. The existing bubble receives visual
values; no Company command or action reference is fabricated for a Group gesture.

An exact original is represented by history only when room, provider alias, sender, logical
ID, content hash and original text agree. Ambiguous matches fail explicitly. Local hiding
consumes the matching command without resurrecting a bubble; redaction retains identity and
clears private text/quote/forward presentation. Confirmed cancellation suppresses stale queued
inventory. Missing sender metadata remains explicit while readable history stays available.
Pending local writes and held originals have per-message status, without inventing delivery.

The route retains placement independently from real timestamps. New pending gestures follow
the visible end despite clock skew, and exact confirmation keeps their existing identity and
position. Sequential and out-of-order acknowledgements cannot move that captured sequence.
Equal snapshots produce equal rows. Placement is bounded presentation state, not a durable
history rewrite or a claim about ordering after the retained route has been destroyed.

The ViewModel publishes composer continuity and rows together before releasing actor-owned
originals. Existing real-use-case tests now also assert immediate rows during delayed local
handoff and stable rows after outbox removal/archived confirmation. Use cases, repositories,
storage formats, network acquisition and native workers are unchanged.

Validation: 66 focused cases passed. The full audit retains all 15,401 preceding case IDs
and adds 42 executions of 21 new shared methods on JVM/browser. Fresh and exactly unchanged
report counts, source hashes and proof artifact identity are in the compact evidence.
Actual execution of these 21 methods on Apple is still pending. The preceding 70 methods
are under separate hosted run 43359439624 at fc451164. No new native run, activated Group
page/route, screenshot, phone installation or measured rendering latency is claimed here.

Next: concrete Group page and route lifetime, shared composer visuals, contextual selection
and actions, bounded warm routes, and first-frame/IME measurements. Existing user interaction
requirements remain: long press begins selection, no permanent selection/network header,
no send spinner, and stable newest-message placement above the composer.

Evidence: `docs/measurements/collaboration-space-thread-rows-2026-11-13.json`.
