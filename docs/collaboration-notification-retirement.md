# Opaque notification discovery and cancellation

The application can discover active notification identities and retire their encrypted
watermarks without receiving denied message previews. These operations use current local
account ownership. They do not require selected-company access, renew credentials,
authenticate a user notification action, or grant permission to publish a card.

`ReadCollaborationNotificationPublications` takes the caller's expected native parent,
an optional opaque cursor and a limit of one through four. It checks the actual session
before and after reading the account journal, within eight seconds. The journal still
decrypts and validates its records internally; the result contains only journal/version,
original-parent, exact-target and state references. Preview text, original content and
action envelopes never leave this cleanup operation. Prepared and Published records are
included; inactive watermarks do not occupy active discovery capacity.

Input bounds, output bounds before copying, strict ordering, unique IDs, forward cursor,
active shape and account/audience ownership are checked. A missing journal returns an
empty page without creating a key or record. The page is a point-in-time discovery result,
so callers must retain exact version/render checks when using it.

`CancelCollaborationNotificationPublication` takes the expected current parent and an
exact discovered reference. Its nonwaiting singleton mutex and eight-second operation
budget bound cleanup. It may retire the same account's older native family even under a
retained company denial. It reads the body internally, compares the complete reference,
then performs an exact CAS to Cancelled with preview and actions removed. Original
timestamp/hash, target and watermark remain. A committed result must have the same
identity/generation and a strictly advancing revision; only opaque settlement metadata
is returned. An already inactive exact reference is unchanged, including its prior
Dismissed state. Stale, absent or replaced records return Contended.

A cancellation whose response is lost stays committed. Subsequent discovery omits it,
and issuance cannot revive that same original. The application surface owner still must
cancel the exact OS card, including after an uncertain settlement. Domain cancellation
does not perform native I/O, and its success alone does not prove that a card is gone.
It also cannot replace authentication of Dismiss/Reply/Read/Mute callbacks.

The owning data module binds the read operation as a factory and cancellation as a
singleton. No journal schema, encryption key or storage adapter changes are needed.
Twelve portable domain cases exercise bounds, denial/older family, paging, malformed
and foreign records, stale references, cancellation, timeout and late results. Three
application tests use the actual Koin graph and encrypted SQLite across reopen for
denied cleanup, a newer privacy render and lost committed cancellation. Their native
surface is a fake; actual notification-input process death remains separate.

See [recorded validation](measurements/chat-notification-retirement-2026-11-03.json) and
the [remaining host integration](collaboration-notification-publication-plan.md).
