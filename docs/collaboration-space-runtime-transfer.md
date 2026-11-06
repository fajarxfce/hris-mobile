# Original runtime freeze and drain transport

The Mobile transport exposes the existing native organization endpoints for an exact
retained device transfer. It does not start a transfer. A typed domain request retains
the original SDK provenance, current organization binding and original handover intent.
Its repository validates those wire coordinates, calls raw API datasources, maps bounded
responses and preserves cancellation and authentication failures. No ViewModel, runtime
owner, local queue or use-case workflow changes in this increment.

Freeze posts the target device, observed device version and original key fingerprint
with the original idempotency key. One native authentication rotation retries that same
body/key. The receipt must name the original legacy device at runtime version1; a target
device receipt is invalid. Drain is one GET over the same three coordinates and accepts
only the exact target/version1, at most eight active requests, and a coherent latest
expiry within sixty seconds of observation. Zero requests requires no latest expiry;
it is not proof of local SDK shutdown or completed publication. Both responses are
limited to1024 bytes. Inputs from another account, audience or native family never
reach HTTP. The current API cannot silently rebind an older native parent.

Seven MockEngine tests cover exact wire identity and headers, native rotation, bounded
zero/eight-slot reads, malformed/oversized responses, wrong provenance/receipts, denied
and repeated unauthorized responses, and cancellation without another request. Two
actual AppContainer cases resolve the new repository through its generated data-module
bindings. Focused acceptance passed9 cases in175.602s with4,263 unchanged inputs and
format/architecture checks. The complete root/Android/browser gate passed5,475 fresh
cases/642 suites in603.54s with zero failures/errors/skips,4,263 unchanged inputs and
zero Android lint/Detekt findings. The tested transport source is committed with
this acceptance record.
[Evidence](measurements/collaboration-space-runtime-transfer-2026-11-06.json).

An owning use case must first establish original private keys and provider publication,
stop/join the exact SDK store, persist its recoverable target, and revalidate current
admission before freeze. Freeze is irreversible for the original runtime. Only the
backend's admitted worker can complete atomic publication and retirement. Old-parent
recovery, SDK adoption, pending-message continuity and actual organization routing remain
required. No live endpoint was called or deployment enabled by this local transport test.
