# Anchored local inbox windows

ReadCollaborationSpaceInboxWindow coordinates the window, directory and session repositories
under current local account/Group admission. It returns one finite bounded window independently
of credential age, selected HR company or native readiness. Confirmed denial terminates the call;
a changed session/account/audience rejects captured data. Repository rows are bounded and copied
before suspended admission. Cancellation rejects even an uncooperative late result. No timers,
background ownership, network acquisition or use-case-to-use-case calls are introduced.

Initial loads use Latest, refresh uses the visible conversation identity, and Older/Newer use
opaque encrypted continuations tied to the exact index generation/revision. Changed is explicit
and never retries the same cursor. A validated MissingAnchor permits exactly one Latest fallback,
at most two local repository reads. Failure and malformed fallback never become empty success.
Empty directional windows terminate. A one-row middle window can have the same boundary ciphertext
in opposite directions; the directional request defines its meaning.

The encrypted repository reuses the existing inbox codec/content keys and calls raw storage only.
Around performs one exact anchor probe, then three indexed before/center/after reads sharing one
raw transaction. At limit50 the three queries request at most104 candidates. Unrelated changes
between probe and snapshot are allowed if the final anchor position and generation still match;
a moving anchor or replaced generation returns Changed. Candidate order, identity, encrypted
content, checkpoint and tags are authenticated. Raw lists and tag sets are detached before any
suspended decryption. No raw schema, query-count or mutation bound changes.

Thirty-four shared additions cover pure bounds, finite admission, cancellation, races, tampering,
mutable snapshots, bidirectional order, account fencing and actual SQLite/IndexedDB reopen. One
application case resolves real DI, publishes local rows, reads around the middle, reopens and
confirms the bounded missing-anchor fallback with no native opens or delivery. All17615 prior
executed identities are retained. Apple test sources compile locally; this is not new Apple
runtime evidence. No UI/phone timing, installed build, Starter port or live rollout is claimed.

The product Pager and retained list still need to preserve viewport, old rows during refresh,
independent labels, pending/draft previews and explicit admission. Group activation and end-to-end
UI/scroll measurements remain open; a finite window contract alone does not prove rendering speed.

Evidence: `docs/measurements/collaboration-inbox-window-2026-11-14.json`.
