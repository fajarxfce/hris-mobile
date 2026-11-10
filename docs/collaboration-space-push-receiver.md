# Organization push reception

`collaboration.space_chat_available` now has an explicit shared application route.
Android's data-only Firebase callback queues it through the shared receiver. Swift
removes provider metadata through its existing bounded envelope adapter, then invokes
the same domain flow. The group lane cannot fall back to company chat or Inbox.

The Kotlin parser accepts exactly the ten V1 fields, or those fields plus both Matrix
room/event locators. It rejects mixed company/group scope, unknown fields, partial
locators, malformed identities, noncanonical numbers and invalid retention intervals.
Neither locators nor packet times authorize content, delivery/read status or key access.
Late valid hints may request recovery without expiring retained local data.

The owning use case captures the current local account/native family, reads its
immutable group root and checks the retained registration. The packet's logical device
must match `Registered.receipt.id`; registration operation IDs and original physical
Matrix device IDs are distinct. Missing/unconfirmed local registration cannot enroll
or manufacture a device. Both LEGACY and SPACE registration provenance can request a
wake; supported runtime acquisition remains a separate worker decision. Expired live
tokens alone do not invalidate local ownership.

A fresh durable group work generation commits before fixed-key OS scheduling.
Every suspending boundary rechecks the captured native parent and cancellation.
Scheduling failure, timeout or a lost post-commit response retains recovery for the
existing startup/resume path. The whole entry is bounded to three seconds, including
at most 1.5 seconds for scheduling. The receiver never acknowledges worker completion.
A late old sweep cannot erase a newer generation.

Duplicate OS packets may request new work generations. There is no receiver-owned
retry loop or SDK acquisition. PUSH revisions are independent of Matrix publication
revisions and are never written into readiness receipts. The existing worker owns
coalescing, finite delivery/backoff, global sync and local idle-demand checks.

Application push composition lives in its owning DI module, with lazy references to
the independently registered workers. Domain construction stays in collaboration data
DI. Android receipt does not run a worker inline. Optional Apple processing retains the
existing 25-second owned callback budget, including cancellation of worker processing.

## Validation

Focused 45 cases pass in 100.7s after splitting push DI from the
worker-registration module. Initial45 tests also passed, but the first gate correctly
failed LongMethod at 106 lines; no rule was suppressed. Domain tests cover parsing,
wrong account/family/root/device/platform, expired credentials, failures at each local
boundary, cancellation, deadlines and retained work. Real application-container tests
use encrypted local root/registration/wake storage, reopen it, preserve old/new work
and verify zero SDK acquisition. Existing company and Inbox routes remain covered.

Full validation passes 6,301 cases in 954.348s: 6,301 fresh and 0 exact reused. Source and archived/current report hashes agree. Root check, Android host/release/lint, shared Wasm/iOS source, 20 browser cases and quality gates pass. See [measurement](measurements/collaboration-space-push-receiver-2026-11-10.json).

Swift 16-case envelope checks are wired into pinned AppleCI with an archived result;
Swift is unavailable on the local Linux host. Full cloud FCM delivery, APNs/NSE,
incoming group decryption/notifications/replies, simultaneous-pusher display dedup,
new SPACE enqueue and product group routing remain separate acceptance. The live group
rollout remains disabled; all 54 communications PRD groups remain open. Release88 was
packaged before this increment and does not contain the new group push receiver.
