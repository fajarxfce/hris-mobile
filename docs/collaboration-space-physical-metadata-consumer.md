# Incoming physical room metadata

An installed SDK can report a room before the application has its logical binding. The incoming
worker now resolves complete owned bindings locally first. Only a missing binding opens one finite
native metadata operation; existing local facts remain usable after live credentials have aged.

The owning Resolve use case reads physical publication metadata from the configured registered
provider, then reads the actual conversation. It validates the full room status, participants,
timestamps, server and room. Published LEGACY rooms additionally require their exact original
Company/conversation metadata and room creator. No fabricated conversation or timestamp is used
to make a physical lookup pass full binding validation.

One invocation owns its fifteen-second live budget and remembered denial. Directory admission,
native parent, installed source and registered provider/device are checked around suspended I/O.
The local path has its own five-second bound without a credential-age gate. Failed or timed-out
cleanup cannot replace a remembered access denial. Cancellation stops the operation. Datasources
perform bounded raw reads; repositories validate/map responses through safeApiCall. There is no
repository-to-repository or usecase-to-usecase dependency.

Canonical conversation metadata and the binding require exact retained readback, including when
the write reply is lost. A boolean reply without the expected durable facts cannot advance incoming
work. After remote acquisition, the use case reloads demand and uses the same pure captured-source
selection policy as the local path. A newer generation remains queued. Exact conversion preserves
its source, FIFO position, head, retry and independent wake; concurrent producer positions, later
hints and inventory loss survive. Known CAS contention keeps the existing short retry.

A known missing-publication API code keeps physical work pending. The prepared original physical
endpoint is not repeatedly fetched and discarded: unpublished Company originals still require their
explicit target and consumer. Native POST replay remains bounded to one credential rotation, with
separate response caps and no selected-company header, provider transport refresh, device-status
poll or per-batch scheduling loop.

Validation accepts 562 focused desktop and 495 browser cases, followed by
11,499 full cases with zero failures/errors/skips. Prior case identities and reused dependency
closures are verified. Checks include formatting, architecture, detekt, Android host/release
compilation/lint, browser/Wasm, iOS metadata and proof APK assembly/unit tests. Failed pre-test
validation runs remain archived: method ownership was clarified and HTTP404 was aligned with the
existing shared error classification, with a real transport regression. Metadata tests exercise
late producer changes, cancellation, finite time budgets, expiry, parent/source/registration
replacement, exact immutable facts, failed cleanup and uncommitted/lost storage replies.

The portable CI workflow now explicitly selects the physical incoming worker test class. An
audited previous run omitted its 12 cases despite passing; local focused validation includes them,
and corrected hosted execution remains a separate requirement.

This increment does not enable raw collection, deploy the API, install a phone build or claim new
native SDK/Apple execution. Original typed routing and storage, complete provider inventory,
keys-only recovery and actual native proof remain required. All 54 PRD groups remain open.

[Exact validation evidence](measurements/collaboration-space-physical-metadata-consumer-2026-11-11.json).

Hosted control at immutable `8ae3d11e`/run42978769594 verifies 9,477 portable cases with all prior
case identities and artifact digests. This includes all 12 physical-worker cases omitted by the
previous workflow selection. Apple control now verifies 3,712 cases, 16 Swift push-field checks
and the unsigned iOS application host. Total hosted acceptance is 13,189 cases. Native SDK8 Apple
execution is not claimed.
