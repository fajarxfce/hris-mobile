# Composer release 66 device evidence

Release 66 (`a923d844`, APK SHA-256
`b90e07756bdf967a20be3f4b1ab59e74f4dbc5be5d0be676808baf27dff83b2a`)
passed signed dev-release validation on the original retained Bima emulator.
The phone still has release 65: its bounded ADB preflight timed out. Installation
remains pending connectivity; no phone UI was driven.

Three new consecutive send gestures produced three ordered, unique bubbles and genuine
Matrix acknowledgements. The software keyboard stayed open, the input stayed editable,
and the next unsent draft survived. Reviewed screenshots and 4-fps lower-viewport video
samples show new bubbles above the composer while typing continued. Offline cold restart
retained all three acknowledgements, earlier history, the next draft and inbox draft preview.
These checks preserved the original application identity, data and encryption keys.

The initial driver failed on a preview assertion that omitted the displayed `Draft:`
prefix, after all three originals were already sent. Its evidence is retained. A separate
read-only continuation verified the prefix and cold offline state without another Send.
Do not rerun that original probe or resend its messages.

Across 180.843 seconds, `m.secret_storage.default_key` was requested twice and returned
404 both times. No 403 was observed. All three encrypted message requests returned 200,
with 175–205 ms to response headers. Host tap-to-request times were 6.492, 8.816 and
7.636 seconds; therefore overall pending latency is still unresolved. The late sync 429
occurred after all three acknowledgements. The two unauthenticated connectivity probes
returned 401; they were not rejected app logins.

Local submit spans took 1518, 1281 and 238 ms. Initial combined draft recovery took
2669 ms; later recovery took 421 ms. These nested/overlapping measurements are not
additive and do not identify a cause by themselves. Further work must separate local
storage, scheduling and worker recovery before changing delivery behavior. Do not infer
that a best-effort scheduling timeout caused the first span solely from similar duration.

The [exact measurement](measurements/collaboration-composer-release66-2026-11-08.json)
records artifact, counters, stage timings and evidence fingerprints. Full source checks
passed 5,893 fresh cases; Starter's exact nine-file generic port passed 250 fresh cases
and was pushed as `e170ada`. Inspector inputs are unchanged from accepted release65;
its OS notification cases were not repeated. SDK5/library packages remain unpublished.
No physical-phone latency, native Apple Matrix or capture-overhead acceptance is claimed.
