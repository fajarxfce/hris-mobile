# Direct transport acquisition

The staging and delivery use cases acquire native device, conversation and published
room coordinates through one current `/direct-conversations/{id}/transport` read per
admission point. Timeline entry and history acquisition retain their existing readers.
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

At this checkpoint source validation is running. No new APK, runtime request-count
measurement or latency improvement is accepted. The current installed release67
remains the prior accepted artifact. This HRIS-specific endpoint and policy do not
belong in the starter.
