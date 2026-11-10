# Admitted Space provider-page acquisition

`AcquireCollaborationSpaceProviderHistory` acquires at most one metadata page through the
installed original runtime. The use case owns current session, directory, room-binding and
transport admission. Repository implementations retain responsibility for their datasources;
no ViewModel, timeline, send queue or replacement SDK client is involved.

Saved progress is read before network acquisition. If that position is exhausted, repeating
`SavedPosition` returns zero references and makes no transport or Matrix request. `Latest` is
an explicit new wave: a completed wave's head becomes its exclusive older boundary, while an
unfinished wave keeps its original boundary. Pending-reference progress remains independent.
A receiver does not need the recipient's sending-readiness state.

The acquired logical runtime and original physical company/device must agree with the current
published transport and retained provider identity. Rotating native sessions do not relabel
physical storage. Each page contains at most 20 immutable metadata references, detached before
subsequent admission checks can suspend. Requested bounds, duplicate IDs, metadata and cursor
progress are validated before storage.

A 15-second invocation budget includes native-session expiry and elapsed gateway validity,
including time spent acquiring the transport. Current ownership and binding are checked around
I/O. SDK credential denials remain authoritative when restriction persistence or runtime cleanup
fails. A conversation-control peer-unavailable response remains distinct from a provider denial.
Cancellation propagates; the use case performs no automatic retry.

The repository atomically retains all page references with the provider position, including
an empty terminal page. A failed compare-and-set or lost commit response returns failure.
The next invocation reads the durable checkpoint before choosing a page; it does not resend a
captured old request or infer cursor ownership from matching event timestamps. This operation
neither resolves pending content nor acknowledges incoming or outgoing recovery work.

Focused validation passes 136 cases in 188.325 seconds: 24 new scenarios and 44 existing
controls on each of desktop and actual Chromium. Shared iOS compilation, formatting,
architecture and detekt pass. Scenarios cover exhaustion, independent bounds, physical
identity, late owner changes, cancellation, timeout, denial through failed cleanup, mutable
provider lists, uncertain commits and competing writers. The initial attempt failed test
compilation on five nullable-pair assertions; their types were corrected before execution.
Full application validation passes 10,401 cases in 909.618 seconds: 8,382 freshly executed
and 2,019 exact accepted controls. Root check, Android host tests and release compilation/lint,
Wasm and iOS application source, actual Chromium, proof APK/unit tests and quality checks pass.
Its candidate removes one unused private flag from the focused candidate; the audit verifies
the exact signature/branch removal and every new case executes again. The original compile-only
failure and the audit correction for ktfmt's collapsed signature are retained.
[Focused measurement](measurements/collaboration-space-page-acquisition-focused-2026-11-10.json)
and [full measurement](measurements/collaboration-space-page-acquisition-full-2026-11-10.json).

[Actual SDK validation](collaboration-space-page-native.md) now verifies both admitted pages
and a repeated exhausted acquisition that adds no HTTP. The earlier
[provider and storage proof](collaboration-space-frontier-native.md) remains accepted.
No product incoming consumer is enabled, and phone latency has not been measured here.
