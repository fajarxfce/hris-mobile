# Direct transport acquisition

The staging and delivery use cases acquire native device, conversation and published
room coordinates through one current `/direct-conversations/{id}/transport` read per
admission point. [Original-window and provider history](collaboration-history-transport.md)
use the same snapshot with their own readmission and recipient-independent metadata
policy. Timeline entry and older-message discovery retain their existing readers.
There is no cached grant or fallback to three requests if the new endpoint is unavailable.
Backend deployment must precede this application's release.

The domain use cases still capture the operation owner, recheck local continuation
after every suspended read, and match the returned device/conversation/room before
borrowing or resuming the paused SDK writer. A pure policy compares the completed
snapshot to that exact operation. Delivery independently re-acquires admission before
resume and after the result; a previously successful snapshot cannot authorize another
phase. Prepared timeline ownership remains distinct from sending authority.

The repository validates and maps one bounded response inside `safeApiCall`. The raw
datasource calls the existing conversation API, which preserves the originating native
session and caps the response at 2 KiB. Neither layer owns retries, scheduling, UI state
or another repository. Cancellation remains owned by the caller. The ViewModel and
composer remain unchanged, as do immutable originals, native transaction identity,
receipt persistence, offline history and the separate server-side Matrix admission.

Source acceptance passed on 8 November 2026: root check, actual Chrome contracts,
Android compilation/lint (zero errors), production web and iOS source compilation,
format, architecture and Detekt. The final gate ran 3,947 fresh cases in 551.969 seconds,
with zero failures/errors/skips; 4,487 application inputs and82 library files were
unchanged throughout. Failed attempts and exact contract/report hashes are retained
in [measurements](measurements/collaboration-direct-transport-2026-11-08.json).

Prepared-queue tests still require independent admission before resume and after
completion, exact room ownership, rejection after current revocation, cancellation
cleanup and original receipt recovery. The coded failure classifications are preserved.

[Release68 is accepted and installed](collaboration-direct-transport-release68.md).
Three actual transport reads returned200; no403 was observed in180.142 seconds.
Tap-to-encrypted-request remains2,957 ms in the emulator sample, so an end-to-end
latency improvement is not claimed. Continue investigating the remaining local
and native queue work. This endpoint and policy stay in HRIS; native Apple,
capture overhead and the remaining54 PRD groups remain separate work.
