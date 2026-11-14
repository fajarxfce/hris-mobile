# Stable recipient bubble rows

RecipientChatMessageIdentity uses the actual account/Group owner, peer and command/provider namespace for its stable visual key. A message can render immediately before canonical association. When the immutable association becomes known, the row gains its real canonical action coordinates without replacing its bubble key, original timestamp or route position. Peer IDs are never treated as conversation IDs and no canonical draft is manufactured.

Recipient originals share the bounded capture validation introduced by the display-continuity projection. After association, their real command intents and exact progress are combined with the canonical command adapter and shared physical-history join. The existing canonical composer remains a separate typed consumer of that join. Company commands preserve their actual company/conversation namespace, including when their UUID equals a Group original. Exact sender, room, event alias, content hash, text and logical identity must match before history consumes an outgoing row; ambiguous evidence fails rather than choosing an arbitrary owner.

The recipient adapter preserves terminal status over stale inventories, respects local hiding and redaction, and retains positions across out-of-order receipts or clock skew. Incoming rows keep the existing selected/original Matrix identity logic. Pending feedback is per message; this pure code never opens transport, retries, acknowledges storage or prepares a conversation. It can be consumed by the existing bubble component without changing its public API.

Recipient drafts currently capture plain text only; the pre-association bubble has no reply target. After association the adapter reads reply metadata from the exact converted intent, which the existing recipient policy currently requires to be absent. Rendering retained historical quotes is not new recipient reply-command support.

Thirteen new common tests cover pending before metadata, identity/position/timestamp through real association and history, clock rollback, independent source retirement, physical namespace collisions, exact evidence mismatches, hidden/redacted content, cancellation, foreign previous rows and snapshot ownership, missing recipient provenance, bounds and ambiguous matches. The 14 recipient display cases and 27 canonical presentation/row cases remain regression controls. Host logic assertions are not measured Android layout/frame performance.

No recipient product route is activated by these adapters. Next evolve the unused composer-only ViewModel into one recipient thread/editor owner, connect the accepted source/destination/progress/history observations with joined cancellation, then actual root/contact/thread UI and rendered acceptance. Independent canonical/recipient drafts, bounded global inbox identity and finite application setup after retained Send still require integration. Phone 93 remains installed and predates this code; Group is OFF, V117–140 undeployed and all 54 PRD groups OPEN. HRIS policy stays out of Starter. Preserve failed Apple evidence, the blocked SQLite preparation and protected stores.

Validation: 18,331 cases (2,199 fresh / 16,132 exact unchanged), preserving all 18,305 prior identities plus 26 executions. Focused 54 and full root/Android/browser/Apple-source gates passed.

Earlier focused attempts are preserved separately; only the accepted source/gates establish this result.

[Exact validation](measurements/collaboration-recipient-rows-2026-11-14.json).
