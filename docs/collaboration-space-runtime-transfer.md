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

## Original publication observation

`publication(owner, transfer)` observes the original provider keys before irreversible
local staging through the organization's legacy-runtime/publication POST route.
It reuses exact original provenance and target coordinates, carries current native
session/client headers, and requires no idempotency key because the server performs
no command mutation. The repository bounds the response to1024bytes and verifies the
complete target/device version, original company/device and retained fingerprint.
The reported observation interval must be positive and no greater than30seconds.

The result is metadata, not a bearer or proof of current private-key possession.
The owning use case must still check freshness, current admission and private-store
ownership before staging, freeze/drain or adoption. Native-parent equality remains;
this transport cannot silently rebind a revoked or replaced original parent.
No SDK, local durable record or server device/runtime changes occur through this read.
Four new transport cases cover native rotation/exact request, malformed/foreign or
oversized response, finite classified rejection and cancellation. Existing invalid
provenance checks include publication. Complete acceptance passed6,096 fresh cases in753 suites
in830.513seconds, with4,271 unchanged inputs and zero failures/errors/skips, Android
lint or Detekt findings. Group remains disabled. The two failed focused attempts and
final acceptance are retained in [the publication record](measurements/collaboration-legacy-publication-transport-2026-11-07.json).

The read-only POST explicitly selects `AuthenticationReplay.ReadOnlyPost` at its
HTTP boundary. Authentication may replay repeatable bytes once after native refresh,
with original scope and no fabricated command key. Ordinary POST, streaming bodies
and other unsafe methods keep their existing restrictions. Cancellation interrupts
refresh/replay. The first transport gate exposed this missing explicit classification;
its original two failures are retained, not weakened to accept a login interruption.
