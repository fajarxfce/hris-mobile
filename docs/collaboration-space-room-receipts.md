# Original room request receipt reads

The room repository can read an exact original request receipt under the current
native owner. Its immutable descriptor retains API audience/account/space,
conversation, operation ID, original native parent/version, device/version,
optional observed request account/revision, and original platform/build. It stores
no local login handle or credential. These are request metadata, not SDK identities.

The API uses the native read-only POST `/room/receipts/read` implemented in Group
`4e1b5b90`. It attaches the CURRENT session and actual client headers, with no
`Idempotency-Key` or company context. The descriptor remains the original body
through the single allowed authentication replay. The response is bounded to4KiB.
The repository acquires and maps within `safeApiCall`, validates current audience
and account/space/conversation ownership, and requires every echoed original field
to match before accepting a bounded receipt naming the same conversation.

A new local login/native parent is allowed for the same account and conversation.
It does not need to match the old descriptor parent. A null receipt remains
uncertain; a later read may find the original. It never permits replacement, grants
room/content access, opens a provider SDK or generates an acknowledgement. Current
server denials/conflicts remain failures; cancellation cannot publish a late result.

The entity/pure policy, API, raw datasource and repository keep separate roles.
Existing Koin registrations provide the extended repository; there is no new
lifecycle coordinator, UI state, user-facing technical copy or business retry.

## Required before owning dispatch

The explicit reader requires a CORRECT complete original descriptor. Current native
token responses and Mobile session storage do not expose native session version;
authentication rotation may change the version used by the first POST. The current
room writer includes that actual version and build in its receipt fingerprint.
A guessed pre-send descriptor cannot recover the first request reliably.

Actual refresh/write characterization and a stable versioned intent or authoritative
persisted descriptor are required before durable owning commands. Preserve original
fingerprints and original encrypted-message IDs. Missing receipt does not fence a
late original; finite replacement requires explicit fencing and retained uncertainty.
No product caller, migration rollout or APK is part of this transport increment.

## Validation

[Accepted source and results](measurements/collaboration-space-room-receipts-2026-11-07.json):
focused93348 passed30 cases/4 suites in183.845s. Full78045 passed3,548 fresh cases
in365.793s on4,354 unchanged inputs, zero failures/errors/skips and zero Android
release lint issues. Root check, Android host tests, format/architecture and Detekt
passed. Earlier unchanged reports reused by Gradle are identified separately.

Tests cover current-parent/build replacement with unchanged original proof,
authentication replay, uncertain absence and late receipt, full fingerprint/account
isolation, input/response bounds, denied/conflicting responses and late cancellation.
No native SDK or phone/provider test was needed for this metadata-only change.
