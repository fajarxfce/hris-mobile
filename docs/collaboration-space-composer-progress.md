# Organization composer message progress

`ObserveCollaborationSpaceComposerProgress` follows at most128 exact caller-retained
Group submissions through the existing repository's active-or-terminal record lookup.
Observed absence preserves the original; disappearance from active inventory never
implies delivery. Every returned record must match the complete immutable intent.
No API, SDK, key creation, writes or delivery scheduling occur here.

Each collector owns its Group admission latch, bounded mailbox and exact-record readers.
Adding, removing or reordering requests preserves unchanged readers; replacing an original
cancels and joins its old reader. Reader tokens reject late callbacks from removed instances.
Mutable native queue sets are captured before suspended admission checks. Known failures
publish without waiting for another record; first success contains actual results for every
requested original. Equal requests and credential rotation do not restart readers.

Account, session, audience and Group denial cancel obsolete work. Confirmed denial remains
closed across failed cleanup and storage reads until an admitted observation. Caller
cancellation joins both the requested-original flow and every content reader. Owned local
facts remain readable with expired credentials and without HR company selection.

Validation:22 focused cases and15,090 full local cases, including40 new JVM/browser executions;
all15,050 preceding identities remain. Android release compile/lint, source checks, Wasm/iOS
compilation, native test-source compilation and proof assembly pass. The20 new shared methods
still require actual hosted Apple execution. This changes no native worker/SDK orchestration
and claims no Group UI activation, phone install or delivery latency improvement.

See [compact evidence](measurements/collaboration-space-composer-progress-2026-11-13.json).
