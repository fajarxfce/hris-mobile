# Owned pending incoming references

`ReadCollaborationSpaceIncomingReferences` reads one authenticated local page, limited to 16
pending references. It retains the captured pass boundary while new provider events arrive,
so a busy room cannot make an existing pass run forever. Ordinary credential age does not hide
owned local progress. Current account and directory denials still gate both sides of storage I/O.
No transport or SDK is opened by this local read.

`CompleteCollaborationSpaceIncomingReferences` admits the exact ordered source page and its
acquisition outcome before writing. The incoming batch carries the original physical provider
company/device alongside its logical runtime. Both must agree with the saved page and current
local owner. Retained original/replacement/redaction outcomes and unsupported events resolve
their source reference; missing keys, missing originals and unconfirmed redactions remain pending.
Resolving a redaction source never substitutes its target identity for the source reference.

The pure policy validates immutable page/batch captures, reference bounds, individual outcomes
and physical ownership. Use cases coordinate repositories and local admission within five seconds;
repositories own their datasources. Exact resolution commits before seek advancement. Uncertain
or rejected resolution never advances the seek, while a failed second commit preserves successful
resolution for the next owned attempt. The caller must retain retry demand after an unfinished
pass; these local operations do not acknowledge either incoming or outgoing recovery wakes.

The raw authenticated store now checks physical provider identity when advancing pending progress
and accepts an explicit page limit. Space uses 16; existing Company callers keep their default of
20. Company formats, authenticated storage frames and provider-page limits remain unchanged.
Native callbacks, automatic incoming consumption and an independent durable incoming worker are
separate integration work. No new polling loop is enabled by this change.

Focused validation passes 296 cases in 367.275 seconds on JVM and actual
Chromium. New coverage consists of eight pure-policy cases, twelve local-usecase cases and three
real-storage cases per target. It covers mutable inputs, cancellation, local credential age,
late denials, failed writes, provider replacement, bounded continuation, new arrivals and restart.
The existing acquisition test also verifies the returned physical identity.

Full application validation passes 10,447 cases in 654.605
seconds: 8,428 freshly executed and 2,019
exact accepted controls. Root check, Android host tests, release compilation/lint, Wasm and iOS
application source, actual Chromium, proof APK/unit tests, formatting, architecture and detekt
pass. All 4,797 source inputs and the inspector library remain unchanged during validation.
[Focused evidence](measurements/collaboration-space-incoming-references-focused-2026-11-10.json)
and [full evidence](measurements/collaboration-space-incoming-references-full-2026-11-10.json).

[Actual SDK acceptance](collaboration-space-incoming-references-native.md) now verifies all
pending references resolved, an empty repeated completion and identical offline reopening.
The previous admitted-page and original-message recovery controls remain. Automatic incoming
consumption is separate work; phone latency and Apple Matrix execution are not measured here.
