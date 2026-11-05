# Native original-device registration flow

The owning feature DI graph now exposes account-owned registration observation,
explicit original LEGACY device preparation and serialized native registration
replay. The new API method shares the existing native-device endpoint stack.
It sends only platform, label, device origin, original legacy company/device and
public-key fingerprint, with the retained Idempotency-Key and exact native session.
The authenticated client's actual current platform/build headers remain unchanged;
old stored build metadata is never used to override them. The response is bounded
to 1 KiB and native 401 rotation remains a single transport replay. Full provider and
physical SDK provenance stays local, not an authority token in the request.

Preparation reads current selected user and native-device metadata, then borrows
the already open original SDK identity. It compares the exact provider user,
original company/device and key fingerprint before retaining immutable intent.
A selected HR workspace is not required by the native flow. Missing selected user,
missing open SDK, changed identity or absent native-device metadata never creates
a new SPACE device. Current preparation explicitly requires original physical
native family/local login to match the current native parent; hard-parent recovery
is a separate unfinished server flow. The original store namespace is never changed.

The preparation invocation owns one fixed native hard deadline and 15 s wall/monotonic
publication budget over local/remote acquisition and insertion. It rechecks session
ownership after suspension and directory admission around insertion. A received
access/MFA denial survives cancellation through bounded cancellation-safe restriction
storage; failed persistence/retry does not reopen the directory. A lost insertion
response retains Pending and repeated preparation returns the original command.
No POST selection, device registration or SDK construction happens during preparation.

The replay singleton includes mutex wait and local reads in the original 15 s budget.
Pending first-send work must still match the actual original SDK identity before
Unconfirmed is persisted. Failed/rejected uncertainty storage prevents HTTP.
Unconfirmed work reuses the exact original operation and body without reopening
an SDK that may already have been frozen by provisioning. A different native parent
cannot replay the original device operation. Registration permits an actual current
build upgrade because its idempotency key excludes build; bootstrap has different
rules. Live SPACE replay remains unavailable until inventory/recovery is implemented.

A canonical device receipt/version 0..999999 is stored as terminal Registered.
The receipt is an owned fact, including after reauthentication and expiry, not
permission to start a group SDK. Newer directory denial prevents current publication
even when the actual receipt was already retained. Deadline/account changes and
cancelled or noncooperative late I/O cannot publish current success; resource cleanup
may finish after the publication deadline. Successful metadata/receipts never clear
directory read admission. Provisioning retry and gateway/SDK adoption remain separate.

The all-parent observer uses local account/session ownership without age, selected
company, client-build or native-lifetime checks. Its final flatMapLatest/buffer0
cancels queued old-account rows on replacement or disposal. It validates bounded,
unique operation/native resources from the repository before publication; this list
is command provenance, not an inventory of private physical SDK stores.

Twenty-eight new cases cover preparation (8), replay (10), local observation (3)
and native transport (7), including cancellation, late results, loss after commit,
account/parent replacement, concurrent denial, original SDK provenance and exact
request replay. Actual AppContainer resolves the observer/preparer and replay
singleton. Focused: 62 cases / 132.36 s. Root :check,
Android-host and dev compilation: 2,659 fresh cases / 331.944 s.
Browser data/domain: 1,579 cases / 136.27 s. All final gates
preserve identical 3,989 build/application inputs; format, architecture
and detekt pass. All gate sessions closed.
[Evidence](measurements/collaboration-space-registration-flow-2026-11-05.json).

Next implement native provisioning retry/bootstrap using the original registration
anchor and current metadata. Then finish retained private-store inventory, explicit
hard-parent recovery, original-store handover/drain/adoption, group runtime/UI and
typed push/background replies. Current caller orchestration/UI remains separate;
this flow is DI-bound but has no activated product route. No live backend deployment,
new APK/phone, provider/FCM, new JNI/private-key proof, Apple runtime or performance
acceptance. All 54 PRD groups remain open; live backend 8b40/schema 116 and installed
phone 29/source 084662c5 stay unchanged.
