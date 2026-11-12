# One local organization contact

`CollaborationSpaceDirectoryRepository.observeContact` reads one published contact by
its account ID. The existing encrypted repository uses the indexed document query,
whose transaction includes account ownership and the visible generation. It decrypts
the authenticated checkpoint and at most one contact, without paging the full directory.

An incomplete replacement keeps the previously published contact. Completion updates
or removes that value; an unrelated contact change does not emit an identical value
again. Empty storage creates no key. Missing/corrupt keys cannot overwrite ciphertext.
The contact's labels grant no HR resource access or communication eligibility; current
session and Group admission remain the domain owner's responsibility.

The source cancels obsolete decryption, preserves storage failures and terminates after
a source error without retry. It adds no transport, writes, cache, timer or new layer.
The existing full-directory and admission operations retain their behavior.

Eight shared regression methods cover the exact indexed query against400 stored
contacts, published replacement/removal, selected-value equality, renewed login,
account/space isolation, authenticated row rejection, missing keys and cancellation.
All17 directory and8 admission cases pass locally. The first new observation test used
virtual deadlines around real I/O; its preserved failure occurred in22ms. Moving that
scenario to Default makes the same5000ms deadlines measure real time. No assertion or
production behavior changed for this correction.

Focused validation passes27 cases. Full validation accepts14,780: 12,742
freshly executed and2,038 exact unchanged cases. All14,764 prior identities
remain with16 new JVM/browser cases. Source checks, Android release compile/lint,
shared Wasm/iOS ARM64 compilation, native test compilation and proof assembly pass.
[Evidence](measurements/collaboration-space-thread-contact-2026-11-12.json) records
the exact source/report/proof digests and preserved failures.

New Apple wrappers instantiate all25 directory/admission methods against native Room
and encryption. Linux compiles these methods and executes0 Apple cases; actual hosted
Apple execution is still required. This increment claims no new phone latency or live
Group rollout. Continue the [organization UI integration](collaboration-organization-ui-plan.md).
