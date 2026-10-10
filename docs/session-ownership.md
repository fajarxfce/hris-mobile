# Local session ownership

Credential refresh compares the complete previous session before publication. A
late token response must not replace credentials that another operation changed.
Sign-out has a different storage condition: delete the original local session ID,
including any tokens rotated while logout was pending.

`SessionRepository.clear(sessionId)` checks that identity and clears the vault,
cache ownership, and in-memory record under the same local commit guard. A false
result means that identity is no longer current. It never deletes a replacement
account. Full-record compare-and-set remains the contract for credential updates.

The repository's private conditional writer owns this storage transaction only.
It does not call the network, retry, request permission, or choose when to log out.
Use cases own those policies. Cancellation before admission preserves the record;
once a vault write begins, publication and cache cleanup finish before releasing
the guard. Storage failure remains explicit and a later read reconciles the cache
with the authoritative vault.

Tests exercise rotation between logout observation and deletion, late credential
publication, replacement accounts, storage failure, and cancellation before and
during the local commit. The contract is shared with Compose Fluent Starter.
