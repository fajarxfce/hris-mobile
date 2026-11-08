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
Build58 device and installation acceptance is recorded below.

The prepared build58 probe owns one per-process log reader on the dedicated emulator,
records one explicit original-account send, and stops collection before leaving the
thread. Raw logs remain local. Its report requires four successful correlated phases
and publishes only closed timing fields. These intervals can overlap asynchronous
delivery: adding them is not an end-to-end acknowledgement or network latency measure.
Actual capture overhead needs a separate comparison with observation disabled.

## Original Android account acceptance

Source09e7eaf7/build58 passes its package/signature/native-byte checks and is installed
in place on the authorized phone, retaining application ID, first installation and data.
The owned original Bima emulator sent one new message, then cold-started offline;
original history and the latest list preview remained visible. Nine screenshots and
three video storyboards (first six seconds at4fps) were inspected. All owned jobs are
reaped and the emulator is stopped. [Exact evidence](measurements/collaboration-send-timing-device-2026-11-07.json).

The four correlated local phases took711ms (draft persistence),294ms (preparation),
211ms (queue),85ms (confirmation). The actual original Matrix send was one HTTP200
attempt with225ms to headers and229ms transfer/59bytes. Coarse UI polls showed a
single pending bubble at2.997s and sent at5.071s, above the composer in both samples.
The full inspector showed98 requests, including82Ktor and16Matrix, and one observation
loss. Clear removed the old send; three fresh capture rows appeared afterward.

These measurements do not isolate the full pending delay, nor do they justify removing
scheduler/authorization checks. Keyboard dismissal during the existing disabled-input
phase remains visible. Separate durable ownership for prepared sends and subsequent
editing remains required before accepting input throughout submission.


## Remaining gap in build 60

Analysis of the existing accepted original-account log gives approximately 1,074 ms
from the first local phase start to the confirmation log, then another 2,212 ms until
the recorded Matrix request begins. Its HTTP response takes 243 ms. Twenty-five Ktor
HTTP operations completed during that second interval, but these closed records cannot
attribute concurrent requests to the send or establish their route. The complete
capture contains 83 Ktor completions. Do not remove admission checks based on this
count alone. [Exact timestamps and limits](measurements/chat-send-gap-2026-11-08.json).

The same sample records Storage/IllegalArgumentException followed by
ChatDelivery_Retire_Storage after the encrypted send. That is a separate investigation:
its exact failing operation and whether it repeats are not yet known. No raw exception
message, account identifier or body is retained in this measurement. This analysis
reuses the existing sample without another send or device interaction; log emission
wall times are approximate boundaries, not a transport-overhead benchmark.

Two concurrent-history races reproduce a storage failure in the retirement lookup;
the [coherent-read correction](collaboration-chat-history-replay.md)
passes real SQLite and IndexedDB cases. This does not itself attribute the device
marker to that lookup. Delivery now reports separate closed failure stages for
`AcquireRetirementHistory` and `RetireCommands`, preserving the original failure and
retry behavior. The next device counter includes fixed HRIS setup/conversation/room
categories as well as Matrix, without retaining paths, headers or message bodies.

## Build61 device result

The original-account sample sends once: Matrix returns HTTP200 in231ms and completes
transfer in232ms; UI polling observes the sent state at4.929s. Local phases take
522/261/251/88ms. The180-second passive counter observes zero403 and four default-key
404 responses. It also observes8 device-setup,8 room and7 conversation responses in
the five seconds before the Matrix send, and41 such responses in the next ten seconds.
These windows include concurrent acquisition; they are not a phone latency benchmark.

The remaining closed failure is now `ChatDelivery_AcquireRetirementHistory_Storage`,
preceded by Storage/IllegalArgumentException. This spans acquisition, receipt recovery
and projection before command retirement. The coherent logical-read correction passes
its regression but has not eliminated this device failure. Isolate that sub-operation
and the repeated admission reads before changing retry or authorization rules.
[Exact device result and installed build61](measurements/chat-retirement-device-2026-11-08.json).

A subsequent emulator-only debugger capture identifies the first throw in
`CollaborationHistoryBatch.raw:133`, during concurrent mutation preparation. Six
real-storage race cases fail on the old code; ten targeted SQLite cases pass after
the bounded local preparation correction. [Diagnosis and restoration evidence](measurements/chat-history-preparation-diagnosis-2026-11-08.json).
Release62 device/request measurements remain pending; debugger timings cannot establish
a production improvement, and the repeated admission reads remain separate work.


## Release62: storage error removed in this sample; latency remains

The original-account emulator sends one new message with no recorded delivery-failure
marker. The inspector measures247ms to the Matrix response and249ms to transfer59bytes;
UI polling observes pending at3.159s/5.321s and sent at7.468s. Local phase durations are
1121/205/147/113ms. This is not an overall latency improvement or phone benchmark.

The180.4-second counter observes zero403, four default-key404, two default-key401 and
one refresh200 across startup/navigation. Before-send5s still has8setup+8room+7conversation
responses; after-send10s drops to8+8+8 (24 vs41 in61). The source of overlapping requests
and local delay still needs profiling. Sixty-six Ktor completions total2264ms of observed
HTTP duration; those overlapping intervals cannot be added as gesture time.

Cold offline restart retains the latest preview and prior original messages. Background
and cold notification taps still open the inspector. All13 screenshots and three cropped
4fps storyboards were inspected; keyboard dismissal remains. Exact ordinary release62
is installed on the phone in place, without UI automation, after the emulator was stopped.
[Exact evidence and limits](measurements/chat-history-preparation-device-2026-11-08.json).


## Local workspace-read measurement

The isolated `workspace-read-cost` scenario uses a fresh database and credential record
in the separate debug proof application. The workspace repository performs no network
I/O and never opens product account records; unrelated emulator/system traffic is not measured. Actual Room, Android Keystore and authenticated decryption
remain in the path. Each invocation checks 61 exact workspace results: one initial
read, 30 sequential reads and two concurrent lanes of 15 reads. Owned records and
keys are removed on completion; the separate 1,536 MiB emulator is stopped.

Sequential reads average 119.523 ms immediately after first boot, then 19.562 ms
in the second invocation of the same APK. The latter averages 8.407 ms for the
checkpoint, 7.206 ms for credential access and 0.305 ms for content decryption.
Android startup/JIT and host load are not independently isolated. These synthetic
read costs cannot be reported as phone or message latency. Parallel durations overlap.
[Exact measurement and build provenance](measurements/workspace-read-cost-2026-11-08.json).

The proof is invoked through `MatrixProofInstrumentation` with
`-e scenario workspace-read-cost` in the separate
`dev.fajar.hris.collaboration.proof` application. Use an owned emulator with no
`protocol-proof/workmanager-enabled.json` marker. Its closed timing output is
`files/protocol-proof/workspace-read-cost.json`; `instrumentation.json` must also
report completion. The fixture creates and cleans only its unique synthetic
database, credential record and Android Keystore alias.

This does not justify caching selected authority or bypassing missing/replaced keys.
The next correction instead targets passive local delivery-status changes that
unnecessarily request admitted original history from the chat screen. Real remote
messages, edits, redactions and explicit entry/recovery must still acquire history.
