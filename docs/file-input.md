# File input

`core/fileio` is an infrastructure adapter for OS document selection and bounded
file reads. It contains no account, permission-request, upload or retention policy.
Data repositories consume `FileInputSource` and map its provider metadata. Domain
and presentation modules never receive SDK handles or import this module.

The application host owns the source and closes it with its container. Android
attaches/detaches the current Activity through a weak reference. Apple attaches
the host controller through a weak reference. Each pending picker owns its
callback/delegate; cancellation unregisters or clears it. Web removes its input
element on completion, dismissal and cancellation. Desktop disposes its dialog.

The calling use case allocates a unique request ID **before** selection and calls
`release` in `finally`, even if selection never returns. A cancelled result can
therefore never leave an unreachable retained input. Only one picker can be open,
at most four selected inputs are retained, and each input allows one reader.
Closing the source rejects future work and cancels pending selection/read jobs.

`select` returns nullable provider facts; `null` as the operation result means
the user dismissed the picker. Metadata is untrusted. A use case decides accepted
types, names, sizes and acquisition deadlines. Selection does not grant permanent
access: Android uses the selected SAF URI without a blanket storage permission;
Apple balances security-scoped URL access. Neither adapter takes a persistent
grant. Copy accepted input into account-owned storage before queuing a transfer.

`read` emits 64 KiB blocks and a shorter final block, with bounded backpressure.
It preserves technical failures and closes the reader on early completion or
cancellation. JVM reads are interruptible; Web aborts the current FileReader;
Apple observes cancellation between bounded native reads and closes the handle.
The Apple adapter does not promise interruption of an in-progress OS syscall.
Releasing input cancels its reader, not business work consuming an already
delivered block. The importing use case owns cancellation of that whole operation.
`Sha256Digest` hashes those blocks incrementally rather than loading a file in RAM.

Repositories use `safeFileCall` around file acquisition and mapping. It propagates
coroutine cancellation, rejects late outcomes and keeps fatal errors thrown.
Stable failure codes distinguish unavailable input, revoked access, a busy picker,
changed input and read failure. Default diagnostics record categories without
file names or paths. This boundary performs no retry, admission or cleanup policy.

Tests cover late selection, request replacement, capacity, duplicate readers,
disposal, pending read cancellation, short reads, zero progress, hashing vectors,
browser DOM cleanup and real browser FileReader blocks. CI also compiles/tests
the Apple target; Linux checks cannot validate UIKit behavior on a device.
