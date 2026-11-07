# Local send timing

Build57's original-account probe observed a239ms Matrix response and240ms body
transfer, while the UI first showed pending at2.876s and sent at5.071s. These are
different boundaries. The host/device clocks were not independently bounded at the
gesture, and polling did not identify the source of the remaining delay.

The current candidate adds four closed performance operations around existing local
work: `DraftPersistence`, `CommandPreparation`, `CommandQueue` and
`CommandConfirmation`. One explicit send coroutine owns their correlation trace;
each measured phase has a distinct span. Explicit departure flushes also measure
`DraftPersistence`, under their own trace. No content, account, conversation, message
ID, credential or free-form error is added to these diagnostic records.

`measureAppResult` belongs to core observability. It records typed failure outcomes
without replacing the returned object or changing exception/cancellation behavior.
Successful false/null results remain successful; callers retain their operation-specific
meaning. Coroutine stacktrace recovery may return a wrapper whose cause is the
original exception. The existing generic measurement boundary owns the monotonic clock
and sample completion, with no additional scope or background collector.

Presentation wraps its existing use-case calls; domain repositories, transport and
delivery policy are unchanged. The draft flush still joins the existing admitted local
writer. Prepare and queue still retain their original bounded scheduler acknowledgement.
Confirmation still requires the exact durable original. This instrumentation does not
resolve keyboard dismissal, pending latency or the placement of submission orchestration.

The focused candidate check passed17 draft/send cases and two actual Ktor/Koin Firebase
module cases. Its original new result-observation test incorrectly required exception
reference identity even under coroutine stacktrace recovery; the correction checks the
same original-or-original-cause contract already used by `PerformanceTest`. All exact
typed-result and outcome assertions remain. The correction passes8 fresh observation
cases. The complete application gate passes5,560 fresh cases with zero failures,
errors or skips on4,427 unchanged inputs in859.724s. Root checks, Android host tests,
release compilation/lint, Wasm/iOS source compilation and quality checks pass; lint
has zero issues. [Exact results and retained first failure](measurements/collaboration-send-timing-local-2026-11-07.json).
No build58 or new device timing is claimed yet.

The prepared build58 probe owns one per-process log reader on the dedicated emulator,
records one explicit original-account send, and stops collection before leaving the
thread. Raw logs remain local. Its report requires four successful correlated phases
and publishes only closed timing fields. These intervals can overlap asynchronous
delivery: adding them is not an end-to-end acknowledgement or network latency measure.
Actual capture overhead needs a separate comparison with observation disabled.
