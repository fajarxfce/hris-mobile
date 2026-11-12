# Bounded account history recovery selection

The recovery reader captures one indexed account metadata row with lookahead,
retaining actual Company/Space owners and historical devices. Internal metadata
may advance an empty page. Runtime, current root, owned local session, directory
admission, installed source and registration are checked around suspensions;
the final recovery revision must still identify the captured turn. Concurrent
primary updates and newer requested generations survive. Captured lists are copied
before later suspension. Invalid pages, stalled reads, denial and cancellation
cannot publish coordinates. One five-second local lifetime covers the operation.

Absent/completed work stays idle. Future operational retry stays deferred; due
retry and backwards wall-clock correction permit a bounded read. No request,
transport, native acquisition, acknowledgement, global advance, source listener
or worker activation is added. Selection evidence is not completion authority.

Twenty-four new common tests cover bounded and malformed metadata, historical
scope, completion/retry, concurrent writes, ownership/source changes, failures,
deadlines and caller cancellation. Existing graph tests resolve the new owner.
Validation remains pending until actual local and hosted results are audited.

The owning data-module factories supply Clock.System for the reader and the existing
lazy physical-binding resolver. Domain constructors retain injectable clocks. The
application graph explicitly resolves both operations, exposing an unregistered
clock that previously remained hidden until first use.

[Exact validation evidence](measurements/collaboration-account-history-recovery-turn-read-2026-11-12.json).
