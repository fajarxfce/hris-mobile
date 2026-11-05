# Known organization cache root

The account-owned root records which organization cache belongs to the account.
It is a locator, not a native access grant, provider identity or SDK-store owner.
Current product routes still use company chat. Group UI must consume directory
admission and current operation authority separately before exposing content or
performing commands.

The root binds audience/account to a single space UUID. The request's local session
fences database reads/writes; local and native session IDs and HR company do not
enter the encryption namespace. New login for the same account reopens the same
ciphertext. A new root is stored once with atomic first publication. Exact replay
preserves its ciphertext; another space cannot silently replace the existing root
and needs explicit migration. A refused write is reconciled against current storage:
the same root is a successful replay, a different root requires migration, and an
absent root is an ordinary state conflict. Read failures remain storage failures.
The reconciliation stays within the original publication budget and rechecks
native ownership before returning.

The record lives beneath the established protected chat-content prefix at
`collaboration-history/space-root/v1`, with its own authenticated purpose. All real
hosts already own the shared protected content key. AAD binds purpose, audience,
account, generation and revision; plaintext is bounded to 1 KiB and encoded
ciphertext to 4 KiB. Root-only records also prevent lost-key regeneration, including
when their account is inactive. Empty reads create no key. This shares encryption
storage infrastructure, without interpreting the space as an HR company.

`ObserveKnownCollaborationSpace` validates the current local account and observes
saved roots without a clock, HR company selection or network request. Same-owner
credential metadata rotation retains one subscription; account/session replacement
cancels a pending result even with a slow consumer. A terminated storage subscription
must be reopened explicitly after recovery.

`PublishKnownCollaborationSpace` accepts an already acquired discovery. It checks
its native owner, original wall/monotonic lifetime and response shape before and
after local publication, within a two-second storage budget. It performs no network
or SDK operation and clears no directory denial. Ordinary root-read availability
remains separate from that finite publication lifetime.

Nine shared storage cases pass on real SQLite and IndexedDB with AES-GCM. Nine
domain cases exercise owned offline reads, pending cancellation, terminated source
recovery, refused-write reconciliation, original admission deadlines and bounded
publication. Real application DI resolves the repository and both use cases.
Root :check, Android-host and Android dev compilation pass in 404.541 s
(2,522 fresh cases); browser data/domain pass in 148.906 s
(1,474 cases), with identical final source inputs.
[Evidence](measurements/collaboration-space-root-2026-11-05.json) distinguishes
earlier attempts and the review correction. This is local storage/application
acceptance; product UI, provider/device adoption and native runtime remain separate.
