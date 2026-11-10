# Organization managed push registration

`ActivateCollaborationSpacePusher` owns one bounded attempt under the current native
account/session, immutable local setup, current directory admission and installed
original runtime. It reads device status once, admits the exact device/gateway and
checks ownership again after provider I/O. It does not enroll a device or open a new
Matrix client. The pure installed-device policy is shared with organization sync;
the two use cases retain their own orchestration and do not call one another.

The Android repository selects the exact organization runtime by owner, logical device
and bootstrap operation. Its existing owned pusher reads the latest bearer from that
same original SDK client and sends one empty managed-pusher request. The server owns
the callback URL, Matrix application ID and stable capability; the phone cannot submit
those fields or send its FCM token through this endpoint. The original company-only
runtime lookup remains separate. Unsupported runtime hosts return unavailable.

The complete attempt has a 15-second budget; native provider I/O retains its existing
10-second budget and owned child cancellation/drain. Caller cancellation propagates;
a late reply cannot acknowledge a replaced session, setup or retired runtime. Confirmed
access restrictions remain retained and close the exact organization runtime even when
storage or cleanup fails. An uncertain reply does not generate a retry, refresh, new
key, SDK client or command rewrite. An explicit later call uses current admission.

Pusher activation is not wired into product recovery yet. Incoming group history,
notification/reply routing and the coexistence of company/group callbacks still need
one-visible-notification acceptance before rollout. This increment provides current
registration through the installed original client; it does not establish actual
FCM/APNs delivery or iOS native Matrix runtime support. The live group flag remains off.

Validation passes 50 focused cases and 6,318 total cases across the required root,
Android host/release/lint, shared Wasm/iOS compilation, browser and proof-host checks.
The final diagnostic-only gate runs 37 fresh cases and verifies 6,281 exact prior
results; unchanged source/archive hashes are checked. A method-scoped coroutine
opt-in removes the new test warning. The new domain cases cover ownership, denial,
cancellation, deadline, busy admission and elapsed authority. Existing Android pusher
transport/child tests and absent-runtime protection are retained.

The actual Android/Synapse experiment passes two cases in 206.26 seconds. The group
scenario makes exactly one managed-pusher request, matches the published capability
hash and original physical identity, then retains that binding across process loss.
Both original transactions complete; the fresh idle sweep makes no additional direct
transport request. No compiler ran during the 2,560 MiB emulator, and it was stopped.
The configured callback and native push token are fictional; no FCM/APNs delivery is
claimed. See [source and native evidence](measurements/collaboration-space-pusher-2026-11-10.json).

An earlier run failed the original-delivery baseline before group registration was
exercised. A command remained unresolved after process loss; the former diagnostics
did not retain its delivery reason. The failed store is preserved. The successor adds
only strict metadata diagnostics and transport counters, without a retry, larger
budget or relaxed assertion. It passes, but the earlier failure remains unexplained
and requires further investigation. A Gradle scheduling failure also preceded the
unchanged successful final build. Both are retained in the evidence.
