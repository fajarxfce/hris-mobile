# Reuse an already joined native room

Android message delivery previously called `Client.joinRoomById` whenever an admitted
worker entered a timeline. That SDK operation sends a join request even when its own
room state is already Joined. Release 86's measured send spent 509 ms acquiring the room
and 704 ms entering its timeline; the complete tap-to-server sample was 2,983 ms. These
stage durations include more than HTTP and are not a latency distribution.

`AndroidCollaborationRoomSource` owns raw room acquisition. It asks the current client
for the exact original room and returns the acquired handle when its membership is
Joined. Missing or other membership states use the existing join operation exactly
once, after disposing the lookup handle. A failed lookup, membership read or disposal
is preserved and cannot silently start another acquisition.

The Android runtime lease owns this datasource and receives the resulting timeline
through its existing child-resource registry. The caller keeps current native,
conversation and device admission in the existing use cases. Room membership grants
no new HRIS authority and the raw adapter never enables a send queue or starts sync.
The original physical SDK store, room ID, transaction IDs and keys stay unchanged.

Cancellation is checked before SDK access and before handing over a returned handle.
A lookup or joined handle returned after cancellation is disposed. Partial failures
keep their original exception and attach a separate disposal failure as suppressed;
a disposed lookup is never closed twice. The borrowed client remains owned by its
runtime parent. Credential rejection after acquisition still closes the child before
a timeline can escape.

With the existing unconditional join retained behind the new datasource, the focused
joined-room regression failed as expected in 66.916 seconds. The corrected adapter passes all
611 Android host cases in 71.209 seconds, including 10 raw acquisition cases and 2 actual
parent/child integration cases, with 4,535 unchanged app inputs and stable inspector
inputs. Formatting, architecture and Detekt pass. Full build 87 validation passes 5,975 fresh cases in 1,290.936 seconds (5,364 desktop and 611
Android host), with no failures/errors/skips or Android release lint issues. Root
check, release compilation, Wasm/iOS source and all quality checks pass with the same
fixed inputs. Reused reports are recorded separately, never added to fresh counts.
[Exact source and gate evidence](measurements/collaboration-joined-room-entry-2026-11-09.json).

Actual retained-emulator latency/HTTP comparison is still pending. This source does
not establish a physical-phone or Apple runtime improvement.
