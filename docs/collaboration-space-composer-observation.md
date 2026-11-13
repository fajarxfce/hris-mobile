# Organization composer observation

`ObserveCollaborationSpaceComposer` observes the encrypted Group draft and retained outbox
independently of message history. An editor read cannot hold up the thread reader. Local
ownership remains readable with expired credentials and without a selected HR company.
No SDK, HTTP, key creation, writes or scheduling occur at this boundary.

Each collector owns its admission latch and child subscriptions. Account, session, audience
or Group changes cancel obsolete readers; late noncancellable results are checked again
before publication. A confirmed denial stays closed across failed cleanup and storage errors
until an admitted observation. The complete bounded outbox is validated and copied before
conversation filtering; held originals remain represented. Equal snapshots and credential
rotation do not restart the local readers. Known failures do not wait for another source's
initial result, while first success contains actual results from both stores.

Validation:20 focused cases and15,050 full local cases, including36 new JVM/browser executions;
all15,014 preceding identities retained. Android release compile/lint, source checks, Wasm/iOS
compilation, native test-source compilation and proof assembly pass. The18 new shared methods
still require actual hosted Apple execution. This increment changes no native worker or SDK
orchestration and adds no UI/phone/latency claim. The failed test-type inference candidate is
retained; no assertion was weakened.

See [compact evidence](measurements/collaboration-space-composer-observation-2026-11-13.json).
