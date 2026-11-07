# Native proof storage diagnostics

The x86_64 worker priority-history case failed with a generic storage result in
first CI42313584318. Its original result and the passing ARM64 run remain recorded.
The dedicated proof process now retains bounded recent storage exception classes
and application/SDK frames; exception messages, file names and values are excluded.
The instrumentation owns the sink and restores the console sink before termination.
Only failure output contains this context; no shipping runtime behavior changes.

Root check,20 fresh proof tests, proof assembly, format and architecture pass in
91.471s on4,269 unchanged inputs, with zero Detekt findings. Tests cover privacy,
cyclic causes, bounded storage and detached snapshots. The exact SDK4 native bytes
match the accepted prior proof. The HRIS CI fixture pins Platform940c181c, whose
parser accepts this optional bounded context;13 native-device,34 HRIS driver and
18 worker-driver tests pass. Its only application differences from the prior pin
are the already validated finite HTTP diagnostic adapter/controller labels.

This is diagnostic readiness, not resolution of the original native or phone
failure. The failed worker scenario will be investigated on fresh owned emulator
data, with original assertions intact and no access to Bima's retained store.
[Evidence](measurements/collaboration-native-storage-diagnostics-2026-11-07.json).
