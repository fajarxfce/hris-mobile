# Finite history recovery turns

Recovery request and page advancement now have explicit finite-pass rules over retained
incoming references. A later request keeps the active cursor and exclusive reference boundary;
replayed active/requested identities never rewind it. A completed current generation stays
visited, while a genuinely later generation starts a fresh finite boundary. These local rules
are not an activated recovery worker or a native-content acknowledgement.

A bounded page contains at most16 pending references. New primary appends cannot enlarge an
active pass; sparse/resolved rows may require one final empty page. Advancement preserves
independent primary changes and a newer requested generation, retains unresolved evidence,
and promotes the latest request at finite EOF. A previously present request cannot silently
vanish. Unresolved outcome counts must fit the attempted page; content/outcome admission remains
with the eventual operation owner.

Both Company and Space use the existing recovery repository. Its raw advancement checks the
exact retained source identities, permits their resolved bit to advance concurrently, validates
the last cursor and reads at most17 current pending indexed rows. That query must share the
decoded current checkpoint. Every still-pending row within the interval being crossed must
belong to the attempted page. A supplied null, partial or empty cursor cannot skip pending rows;
lookahead catches an unattempted seventeenth row without scanning unbounded history. New rows
beyond the captured boundary remain for later work.

The checkpoint update uses revision compare-and-set. Changed query/commit/pass state returns
contention; an already-advanced old page is left for the owning operation to read back/reselect.
There is no hidden raw retry or rewind. Reference rows, original content, provider positions,
primary pending seek and existing encryption schema stay intact. Cancellation propagates;
a committed update with a lost reply remains recoverable after reopen.

Validation adds20 common domain cases and15 actual database cases to the existing20, making35
storage cases on each tested platform, each exercising both Company and Space. Cases include
continuous requests while traversing37 references, finite bounds and promotion, sparse rows,
omitted middle references, forged EOF, lookahead, concurrent resolution/requests/query/commit,
cancellation and lost reply across reopen. Existing schema, primary history and dependency graph
contracts remain controls. 13,002 local cases were accepted with prior case identities and
unchanged dependency closures verified. Exact evidence is linked below.

Global recovery discovery/scheduling, admitted native-content and original-resolution owners,
primary EOF demotion plus recovery wake, trigger-specific retries and SDK key-listener ownership
remain separate work. No incoming producer/collector, key/provider request, third reference
catalog, new native client or invented sync position is introduced. Full blocked-queue liveness,
native SDK8 Apple and combined Company/Group execution remain open. All54 PRD groups remain open;
no Starter or live deployment changed.

[Exact validation evidence](measurements/collaboration-history-recovery-turn-2026-11-11.json).
