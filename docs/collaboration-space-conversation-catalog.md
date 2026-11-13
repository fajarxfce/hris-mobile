# Local organization conversation catalog

The catalog reads the existing encrypted canonical conversation indexes without rewriting
them or reading message history. It exposes published metadata versions and bounded pages
for building the persistent inbox activity index. Its opaque order is not recent-chat order.

Each call reads at most50 index rows plus one lookahead, then verifies both indexes of each
returned canonical pair. Cursor authentication binds account, server and Group; a renewed
local login can continue the same owned pass. The cursor freezes generation and insertion
revision. Insertions after that revision request a later pass, including insertions behind
the cursor. Peer-only pages can be empty while still carrying next; only next=null indicates
completion. Generation replacement/removal returns an explicit Restart, not successful absence.

The raw reader ignores staging, checks ownership around publication, rejects missing peer
indexes and damaged ciphertext, and propagates cancellation. Missing local metadata needs
no key; missing keys over retained records cannot create replacements. Version observation
reads no message bodies and suppresses equal values. Admission/scheduling stay in domain.

Twelve real storage methods run on desktop and browser. The28-case focused gate also keeps
the14 canonical metadata contracts and two app composition cases. The full gate retains all
15,517 prior identities and adds24 executions, totaling15,541. Apple test compilation passes;
the twelve actual Apple executions remain for the next hosted control. One failed candidate
is retained: a new test raw() extension was shadowed by the existing history fixture member.
Renaming only that fixture accessor to rawCatalog() corrected the three failures; original
test names and assertions remain intact.

This is catalog storage, not a visible inbox, activity projection, Group rollout or native
SDK/runtime change. The next domain increment admits this catalog under account/Group
ownership, followed by the encrypted activity index, pending/draft previews and actual hub.

Evidence: `docs/measurements/collaboration-space-conversation-catalog-2026-11-13.json`.

## Actual Apple and portable verification

Combined control43379266527 at047584f125b182d8bd811ac343fb6a4ca30fbc9a passes18,816 cases,
including13,306 portable and5,510 actual Apple cases. All42 new navigation/catalog/admission
methods run on Apple; the iOS host and16 push-field cases pass. Earlier pending native statuses
above are superseded by this result. No product Group graph or live rollout is enabled.
Evidence: `docs/measurements/collaboration-space-catalog-hosted-2026-11-13.json`.
