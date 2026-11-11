# Room inventory under the installed native owner

The existing Android runtime now exposes a finite room-inventory capability. A request names its
exact Space runtime and original SDK source. The registry must still own that runtime, device and
operation, and the supplied source must equal the original store/user/device before enumeration.
Current registry ownership and credential failures are checked again after the read.

The installed parent's existing SdkChildResources owns raw enumeration and DTO mapping. Cancelling
or timing out a waiter cannot destroy the client underneath late native I/O. A pending read retains
its child slot until it finishes; shutdown drains the child before releasing native/store resources.
Concurrent requests keep independent captured pages. No extra client, store, sync loop, collector,
background task, implicit join or transport request is introduced.

Mapping verifies the requested exclusive cursor, finite limit, ordered unique room IDs and valid
continuation at the last row of a full page. Every native membership remains distinct. Metadata is
detached from mutable SDK lists and grants no content access. The existing classified SDK wrapper
preserves authorization failures; a simultaneous local/native failure cannot erase a known denial.

The owning data module binds the capability to the existing runtime implementation. Unsupported
hosts return Unavailable, preserving caller cancellation; an absent native capability cannot claim
an empty or completed inventory. iOS has this explicit fallback until its native implementation is
provided. Shared compilation and hosted tests do not establish native inventory execution on Apple.

Fourteen Android reader cases cover cancellation, timeout, capacity, denial, malformed metadata and
reverse completion order. Three actual-registry adoption cases verify original source identity,
zero enumeration for foreign/unpublished/retired requests and Room disposal before parent release.
They use native/store fakes, not a real native SDK or database. Twelve common mapper cases and one
unavailable-host case run on desktop, Android host and browser; existing controls remain intact.
Focused97 desktop/Android and26 browser cases pass. Full local validation verifies 12,402 cases,
preserved prior IDs, unchanged retained dependency closures, required lint/architecture/formatting,
Android release compilation, shared Wasm/iOS metadata and proof APK/unit targets.

SDK8 still allocates the complete Room handle list; finite output is not a native allocation bound.
Durable inventory generation/cursor/retry retention, membership/absence policy and admitted worker
integration remain separate. Pending-key backpressure must be solved so the64-task bound cannot
starve other rooms. No pending references may be dropped to make that queue appear complete.
Raw collection and Original production remain disabled. No live deployment or phone installation
is claimed; all54 communications PRD groups remain open. There is no generic Starter change.

[Exact validation evidence](measurements/collaboration-space-owned-room-inventory-2026-11-11.json).
